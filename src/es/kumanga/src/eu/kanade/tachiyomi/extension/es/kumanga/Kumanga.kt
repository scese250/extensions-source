package eu.kanade.tachiyomi.extension.es.kumanga

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class Kumanga : HttpSource(), ConfigurableSource {

    override val name = "KuManga"

    override val baseUrl = "https://www.kumanga.com"

    private val apiUrl = "$baseUrl/backend/ajax/searchengine2.php"

    override val lang = "es"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")

    override val client: OkHttpClient = super.client.newBuilder()
        .setRandomUserAgent(
            preferences.getPrefUAType(),
            preferences.getPrefCustomUA(),
        )
        .rateLimit(2)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("keywords", "")
            .add("contentType", "manga")
            .add("page", page.toString())
            .add("perPage", CONTENT_PER_PAGE.toString())
            .build()

        val reqHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return POST(apiUrl, reqHeaders, formBody)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonResult = json.decodeFromString<ComicsPayloadDto>(response.body.string())
        val mangas = jsonResult.contents.map { it.toSManga(baseUrl) }
        val hasNextPage = jsonResult.retrievedCount >= CONTENT_PER_PAGE
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val cleanQuery = query.trim()
        val kw = cleanQuery.replace(Regex("""[^a-zA-Z0-9\u00C0-\u017F]+"""), "%").trim('%')
        val searchTerm = if (kw.isNotEmpty()) kw else cleanQuery

        val formBody = FormBody.Builder()
            .add("keywords", searchTerm)
            .add("contentType", "manga")
            .add("page", page.toString())
            .add("perPage", CONTENT_PER_PAGE.toString())

        filters.forEach { filter ->
            when (filter) {
                is TypeList -> {
                    filter.state
                        .filter { it.state }
                        .forEach { formBody.add("type_filter[]", it.id) }
                }
                is StatusList -> {
                    filter.state
                        .filter { it.state }
                        .forEach { formBody.add("status_filter[]", it.id) }
                }
                is GenreList -> {
                    filter.state
                        .filter { it.state }
                        .forEach { formBody.add("category_filter[]", it.id) }
                }
                else -> {}
            }
        }

        val reqHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return POST(apiUrl, reqHeaders, formBody.build())
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.km-img-gral-2 img")?.attr("abs:src")
        description = document.selectFirst("p.Mdesc")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select("a[href*=categories=]").joinToString { it.text().trim() }

        val statusText = document.select("div.media-info-list__item:contains(Estado) .media-info-list__value").text()
        status = parseStatus(statusText)
    }

    private fun parseStatus(status: String) = when {
        status.contains("En emisión", ignoreCase = true) || status.contains("Activo", ignoreCase = true) -> SManga.ONGOING
        status.contains("Finalizado", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()

        // 1. Priority: application/ld+json contains full structured list of chapters
        for (script in document.select("script[type=application/ld+json]")) {
            val jsonStr = script.data()
            if (jsonStr.isBlank()) continue
            try {
                val jsonElement = json.parseToJsonElement(jsonStr)
                val examples = jsonElement.jsonObject["workExample"]?.jsonArray
                if (examples != null) {
                    for (ex in examples) {
                        val url = ex.jsonObject["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isNotBlank() && seenUrls.add(url)) {
                            val numMatch = Regex("""/capitulo/([0-9]+(?:\.[0-9]+)?)""").find(url)
                            val numStr = numMatch?.groupValues?.get(1).orEmpty()
                            val nameStr = ex.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val chapterName = if (numStr.isNotEmpty()) {
                                if (nameStr.isNotEmpty() && nameStr != "Capítulo $numStr") "Capítulo $numStr - $nameStr" else "Capítulo $numStr"
                            } else {
                                if (nameStr.isNotEmpty()) nameStr else "Capítulo"
                            }
                            chapters.add(
                                SChapter.create().apply {
                                    this.name = chapterName
                                    setUrlWithoutDomain(url)
                                    chapter_number = numStr.toFloatOrNull() ?: -1f
                                },
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: DOM links with /capitulo/
        if (chapters.isEmpty()) {
            for (a in document.select("a[href*=/capitulo/]")) {
                val url = a.attr("abs:href")
                if (url.isNotBlank() && seenUrls.add(url)) {
                    val numMatch = Regex("""/capitulo/([0-9]+(?:\.[0-9]+)?)""").find(url)
                    val numStr = numMatch?.groupValues?.get(1).orEmpty()
                    val text = a.text().trim()
                    chapters.add(
                        SChapter.create().apply {
                            this.name = if (text.isNotEmpty()) text else "Capítulo $numStr"
                            setUrlWithoutDomain(url)
                            chapter_number = numStr.toFloatOrNull() ?: -1f
                        },
                    )
                }
            }
        }

        // 3. Fallback: OTHER_CHAPTERS in script
        if (chapters.isEmpty()) {
            val midMatch = Regex("""/manga/(\d+)""").find(document.location())
            val mid = midMatch?.groupValues?.get(1).orEmpty()
            val scriptText = document.select("script:containsData(OTHER_CHAPTERS)").toString()
            val otherMatch = Regex("""let\s+OTHER_CHAPTERS\s*=\s*(\[.*?\]);""").find(scriptText)
            if (otherMatch != null && mid.isNotEmpty()) {
                try {
                    val array = json.parseToJsonElement(otherMatch.groupValues[1]).jsonArray
                    for (entry in array) {
                        val numStr = entry.jsonObject["NumCap"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val titleStr = entry.jsonObject["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val url = "$baseUrl/manga/$mid/capitulo/$numStr"
                        if (seenUrls.add(url)) {
                            chapters.add(
                                SChapter.create().apply {
                                    this.name = "Capítulo $numStr" + if (titleStr.isNotEmpty()) " - $titleStr" else ""
                                    setUrlWithoutDomain(url)
                                    chapter_number = numStr.toFloatOrNull() ?: -1f
                                },
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return chapters
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url.toString()
        val doc = response.asJsoup()

        // Extract chapter ID (cid)
        var cid = Regex("""/(?:leer|c)/(\d+)""").find(requestUrl)?.groupValues?.get(1).orEmpty()
        if (cid.isEmpty()) {
            val link = doc.selectFirst("a[href*=/leer/], a[href*=/c/]")?.attr("href").orEmpty()
            cid = Regex("""/(?:leer|c)/(\d+)""").find(link)?.groupValues?.get(1).orEmpty()
        }
        if (cid.isEmpty()) {
            cid = Regex("""(?:manga/(?:leer|c)/)(\d+)""").find(doc.html())?.groupValues?.get(1).orEmpty()
        }

        // 1. Try jaja decryption if available
        var jajaStr: String? = null
        val scriptJaja = doc.selectFirst("script:containsData(jaja)")?.data()
        if (scriptJaja != null) {
            jajaStr = Regex("""(?:const|var|let)?\s*jaja\s*=\s*["']([^"']+)["']""").find(scriptJaja)?.groupValues?.get(1)
        }

        if (jajaStr == null && cid.isNotEmpty() && !requestUrl.contains("/leer/")) {
            try {
                val leerReq = GET("$baseUrl/manga/leer/$cid", headersBuilder().set("Referer", requestUrl).build())
                val leerResp = client.newCall(leerReq).execute()
                if (leerResp.isSuccessful) {
                    val s = leerResp.asJsoup().selectFirst("script:containsData(jaja)")?.data()
                    if (s != null) {
                        jajaStr = Regex("""(?:const|var|let)?\s*jaja\s*=\s*["']([^"']+)["']""").find(s)?.groupValues?.get(1)
                    }
                }
                leerResp.close()
            } catch (_: Exception) {}
        }

        if (!jajaStr.isNullOrBlank()) {
            val decryptedUrls = decryptJaja(jajaStr)
            if (decryptedUrls.isNotEmpty()) {
                return decryptedUrls.mapIndexed { index, url ->
                    Page(index, "$baseUrl/", url)
                }
            }
        }

        // 2. Direct probe against KuManga's databank CDN (databank.kumanga.com)
        if (cid.isNotEmpty()) {
            val probeHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
            val pages = mutableListOf<Page>()
            var pIdx = 1
            var finished = false

            while (!finished && pIdx <= 400) {
                val chunkSize = 10
                val chunkPages = (pIdx until pIdx + chunkSize).toList()
                val futures = chunkPages.map { p ->
                    val imgUrl = "https://databank.kumanga.com/manga/$cid/$p.jpg"
                    val headReq = Request.Builder().url(imgUrl).head().headers(probeHeaders).build()
                    client.dispatcher.executorService.submit<Pair<Int, Boolean>> {
                        try {
                            client.newCall(headReq).execute().use { r ->
                                Pair(p, r.isSuccessful)
                            }
                        } catch (_: Exception) {
                            Pair(p, false)
                        }
                    }
                }

                val results = futures.map { it.get() }.toMap()
                for (p in chunkPages) {
                    if (results[p] == true) {
                        pages.add(Page(p - 1, "$baseUrl/", "https://databank.kumanga.com/manga/$cid/$p.jpg"))
                    } else {
                        finished = true
                        break
                    }
                }
                pIdx += chunkSize
            }

            if (pages.isNotEmpty()) {
                return pages
            }
        }

        throw Exception("No fue posible obtener las páginas del capítulo")
    }

    private fun decryptJaja(jajaVal: String): List<String> {
        return try {
            val key = "Jr54VwepF4La"
            val rawBytes = Base64.decode(jajaVal, Base64.DEFAULT)
            val decryptedChars = CharArray(rawBytes.size) { i ->
                val byteVal = rawBytes[i].toInt() and 0xFF
                val keyChar = key[i % key.length].code
                (byteVal xor keyChar).toChar()
            }
            val jsonString = String(decryptedChars)
            val items = json.decodeFromString<List<String>>(jsonString)
            items.map { item ->
                if ("src=" in item) {
                    val hexStr = item.substringAfter("src=").substringBefore("&")
                    try {
                        val bytes = hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        String(bytes, Charsets.UTF_8)
                    } catch (_: Exception) {
                        if (item.startsWith("/")) "$baseUrl$item" else item
                    }
                } else {
                    if (item.startsWith("/")) "$baseUrl$item" else item
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList() = FilterList(
        TypeList(getTypeList()),
        Filter.Separator(),
        StatusList(getStatusList()),
        Filter.Separator(),
        GenreList(getGenreList()),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        addRandomUAPreferenceToScreen(screen)
    }

    private class Type(name: String, val id: String) : Filter.CheckBox(name)
    private class TypeList(types: List<Type>) : Filter.Group<Type>("Filtrar por tipos", types)

    private class Status(name: String, val id: String) : Filter.CheckBox(name)
    private class StatusList(status: List<Status>) : Filter.Group<Status>("Filtrar por estado", status)

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Filtrar por géneros", genres)

    private fun getTypeList() = listOf(
        Type("Manga", "1"),
        Type("Manhwa", "2"),
        Type("Manhua", "3"),
        Type("One shot", "4"),
        Type("Doujinshi", "5"),
    )

    private fun getStatusList() = listOf(
        Status("Activo", "1"),
        Status("Finalizado", "2"),
        Status("Inconcluso", "3"),
    )

    private fun getGenreList() = listOf(
        Genre("Acción", "1"),
        Genre("Artes marciales", "2"),
        Genre("Automóviles", "3"),
        Genre("Aventura", "4"),
        Genre("Ciencia Ficción", "5"),
        Genre("Comedia", "6"),
        Genre("Demonios", "7"),
        Genre("Deportes", "8"),
        Genre("Doujinshi", "9"),
        Genre("Drama", "10"),
        Genre("Ecchi", "11"),
        Genre("Espacio exterior", "12"),
        Genre("Fantasía", "13"),
        Genre("Gender bender", "14"),
        Genre("Gore", "46"),
        Genre("Harem", "15"),
        Genre("Hentai", "16"),
        Genre("Histórico", "17"),
        Genre("Horror", "18"),
        Genre("Josei", "19"),
        Genre("Juegos", "20"),
        Genre("Locura", "21"),
        Genre("Magia", "22"),
        Genre("Mecha", "23"),
        Genre("Militar", "24"),
        Genre("Misterio", "25"),
        Genre("Música", "26"),
        Genre("Niños", "27"),
        Genre("Parodia", "28"),
        Genre("Policía", "29"),
        Genre("Psicológico", "30"),
        Genre("Recuentos de la vida", "31"),
        Genre("Romance", "32"),
        Genre("Samurai", "33"),
        Genre("Seinen", "34"),
        Genre("Shoujo", "35"),
        Genre("Shoujo Ai", "36"),
        Genre("Shounen", "37"),
        Genre("Shounen Ai", "38"),
        Genre("Sobrenatural", "39"),
        Genre("Súperpoderes", "41"),
        Genre("Suspenso", "40"),
        Genre("Terror", "47"),
        Genre("Tragedia", "48"),
        Genre("Vampiros", "42"),
        Genre("Vida escolar", "43"),
        Genre("Yaoi", "44"),
        Genre("Yuri", "45"),
    )

    companion object {
        private const val CONTENT_PER_PAGE = 24
    }
}
