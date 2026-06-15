package eu.kanade.tachiyomi.extension.es.manhwalatino

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class ManhwaLatino :
    Madara(
        "Manhwa-Latino",
        "https://manhwa-latino.com",
        "es",
        SimpleDateFormat("dd/MM/yyyy", Locale("es")),
    ),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()

            val isImageRequest = request.url.toString().substringBefore("?").let {
                it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) ||
                    it.endsWith(".png", true) || it.endsWith(".webp", true)
            }

            val removeEncoding = isImageRequest && preferences.getBoolean(
                REMOVE_ENCODING_PREF,
                REMOVE_ENCODING_DEFAULT,
            )

            val newRequest = if (removeEncoding) {
                request.newBuilder().removeHeader("Accept-Encoding").build()
            } else {
                request
            }

            val response = chain.proceed(newRequest)

            if (removeEncoding && response.header("Content-Type")?.contains("application/octet-stream", true) == true) {
                val orgBody = response.body
                val newBody = orgBody.source().asResponseBody("image/jpeg".toMediaType())
                return@addInterceptor response.newBuilder()
                    .header("Content-Type", "image/jpeg")
                    .body(newBody)
                    .build()
            }

            return@addInterceptor response
        }
        .rateLimit(
            preferences.getString(RATE_LIMIT_PREF, RATE_LIMIT_DEFAULT)!!.toInt(),
            2.seconds,
        )
        .build()

    override val useNewChapterEndpoint = true

    override val chapterUrlSelector = "div.mini-letters > a"

    override val mangaDetailsSelectorStatus = "div.post-content_item:contains(Estado del comic) > div.summary-content"
    override val mangaDetailsSelectorDescription = "div.post-content_item:contains(Resumen) div.summary-container"
    override val pageListParseSelector = "div.page-break img.wp-manga-chapter-img"

    private val chapterListNextPageSelector = "div.pagination > span.current + span"

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url
        var document = response.asJsoup()
        launchIO { countViews(document) }

        val chapterList = mutableListOf<SChapter>()
        var page = 1

        do {
            val chapterElements = document.select(chapterListSelector())
            if (chapterElements.isEmpty()) break
            chapterList.addAll(chapterElements.map { chapterFromElement(it) })

            val hasNextPage = document.selectFirst(chapterListNextPageSelector) != null
            if (hasNextPage) {
                page++
                val nextPageUrl = mangaUrl.newBuilder().setQueryParameter("t", page.toString()).build()
                document = client.newCall(GET(nextPageUrl, headers)).execute().asJsoup()
            } else {
                break
            }
        } while (true)

        return chapterList
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        with(element) {
            selectFirst(chapterUrlSelector)!!.let { urlElement ->
                chapter.url = urlElement.attr("abs:href").let {
                    it.substringBefore("?style=paged") + if (!it.endsWith(chapterUrlSuffix)) chapterUrlSuffix else ""
                }
                chapter.name = urlElement.wholeText().substringAfter("\n").trim()
            }

            chapter.date_upload = selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
                ?: selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
                ?: parseChapterDate(selectFirst(chapterDateSelector())?.text())
        }

        return chapter
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = RATE_LIMIT_PREF
            title = "Peticiones por ventana de tiempo"
            summary = "Cantidad de peticiones permitidas cada 2 segundos.\n" +
                "Valores altos pueden causar bloqueos por Cloudflare.\n" +
                "Requiere reiniciar la app."
            entries = RATE_LIMIT_ENTRIES
            entryValues = RATE_LIMIT_VALUES
            setDefaultValue(RATE_LIMIT_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = REMOVE_ENCODING_PREF
            title = "Desactivar compresion en imagenes"
            summary = "Elimina el header Accept-Encoding en peticiones de imagenes.\n" +
                "Activar si las imagenes no cargan correctamente.\n" +
                "Desactivar para mayor velocidad de descarga."
            setDefaultValue(REMOVE_ENCODING_DEFAULT)
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val RATE_LIMIT_PREF = "pref_rate_limit"
        private const val RATE_LIMIT_DEFAULT = "1"
        private val RATE_LIMIT_ENTRIES = arrayOf(
            "1 peticion / 2s (por defecto)",
            "2 peticiones / 2s",
            "3 peticiones / 2s",
            "4 peticiones / 2s",
            "5 peticiones / 2s",
        )
        private val RATE_LIMIT_VALUES = arrayOf("1", "2", "3", "4", "5")

        private const val REMOVE_ENCODING_PREF = "pref_remove_encoding"
        private const val REMOVE_ENCODING_DEFAULT = true

        private const val RESTART_APP_MESSAGE = "Reinicie la aplicacion para aplicar los cambios"
    }
}
