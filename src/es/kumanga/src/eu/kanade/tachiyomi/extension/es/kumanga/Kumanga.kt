package eu.kanade.tachiyomi.extension.es.kumanga

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class Kumanga : HttpSource() {

    override val name = "KuManga"

    override val baseUrl = "https://www.kumanga.com"

    private val apiUrl = "$baseUrl/backend/ajax/searchengine2.php"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private var rCookie: String? = null

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (rCookie != null) {
                val existing = req.header("Cookie")
                if (existing != null) {
                    if (!existing.contains(rCookie!!)) {
                        builder.header("Cookie", "$existing; $rCookie")
                    }
                } else {
                    builder.header("Cookie", rCookie!!)
                }
            }
            val res = chain.proceed(builder.build())
            val setCookies = res.headers("Set-Cookie")
            val rCookieHeader = setCookies.find { it.startsWith("__r=") }
            if (rCookieHeader != null) {
                rCookie = rCookieHeader.substringBefore(";")
            }
            res
        }
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

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.h_move").mapNotNull { element ->
            val linkManga = element.selectFirst("a.mhu-name") ?: return@mapNotNull null
            val linkChapter = element.selectFirst("a.mhu-card")

            SManga.create().apply {
                val rawHref = linkManga.attr("href")
                url = if (rawHref.startsWith("http")) {
                    rawHref.removePrefix(baseUrl)
                } else if (rawHref.startsWith("/")) {
                    rawHref
                } else {
                    "/$rawHref"
                }
                title = linkManga.text().trim()

                if (linkChapter != null) {
                    val bgImage = linkChapter.attr("style")
                    val bgUrl = bgImage.substringAfter("url(").substringBefore(")").trim('"', '\'')
                    if (bgUrl.isNotEmpty()) {
                        thumbnail_url = if (bgUrl.startsWith("http")) bgUrl else "$baseUrl/$bgUrl"
                    }
                }
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, false)
    }

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

        val currentPath = response.request.url.encodedPath
        val mangaId = currentPath.substringAfter("/manga/").substringBefore("/")

        // 1. Priority: application/ld+json contains full structured list of chapters
        for (script in document.select("script[type=application/ld+json]")) {
            val jsonStr = script.data()
            if (jsonStr.isBlank()) continue
            try {
                val jsonElement = json.parseToJsonElement(jsonStr)
                val examples = jsonElement.jsonObject["workExample"]?.jsonArray
                if (examples != null) {
                    for (ex in examples) {
                        val rawUrl = ex.jsonObject["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (rawUrl.isNotBlank()) {
                            val numMatch = Regex("""/capitulo/([0-9]+(?:\.[0-9]+)?)""").find(rawUrl)
                            val numStr = numMatch?.groupValues?.get(1).orEmpty()
                            val cleanPath = if (mangaId.isNotEmpty() && numStr.isNotEmpty()) {
                                "/manga/$mangaId/capitulo/$numStr"
                            } else {
                                rawUrl.removePrefix(baseUrl).let { if (it.startsWith("/")) it else "/$it" }
                            }
                            if (seenUrls.add(cleanPath)) {
                                val nameStr = ex.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val chapterName = if (numStr.isNotEmpty()) {
                                    if (nameStr.isNotEmpty() && nameStr != "Capítulo $numStr") "Capítulo $numStr - $nameStr" else "Capítulo $numStr"
                                } else {
                                    if (nameStr.isNotEmpty()) nameStr else "Capítulo"
                                }
                                chapters.add(
                                    SChapter.create().apply {
                                        this.name = chapterName
                                        this.url = cleanPath
                                        this.chapter_number = numStr.toFloatOrNull() ?: -1f
                                    },
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: DOM links with /capitulo/
        for (a in document.select("a[href*=/capitulo/]")) {
            val rawHref = a.attr("href")
            val numMatch = Regex("""/capitulo/([0-9]+(?:\.[0-9]+)?)""").find(rawHref)
            val numStr = numMatch?.groupValues?.get(1).orEmpty()
            val cleanPath = if (mangaId.isNotEmpty() && numStr.isNotEmpty()) {
                "/manga/$mangaId/capitulo/$numStr"
            } else {
                rawHref.removePrefix(baseUrl).let { if (it.startsWith("/")) it else "/$it" }
            }
            if (seenUrls.add(cleanPath)) {
                val text = a.text().trim()
                chapters.add(
                    SChapter.create().apply {
                        this.name = if (text.isNotEmpty()) text else "Capítulo $numStr"
                        this.url = cleanPath
                        this.chapter_number = numStr.toFloatOrNull() ?: -1f
                    },
                )
            }
        }

        // 3. Fallback: OTHER_CHAPTERS in script
        val scriptText = document.select("script:containsData(OTHER_CHAPTERS)").toString()
        val otherMatch = Regex("""let\s+OTHER_CHAPTERS\s*=\s*(\[.*?\]);""").find(scriptText)
        if (otherMatch != null && mangaId.isNotEmpty()) {
            try {
                val array = json.parseToJsonElement(otherMatch.groupValues[1]).jsonArray
                for (entry in array) {
                    val numStr = entry.jsonObject["NumCap"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (numStr.isEmpty()) continue
                    val cleanPath = "/manga/$mangaId/capitulo/$numStr"
                    if (seenUrls.add(cleanPath)) {
                        val titleStr = entry.jsonObject["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        chapters.add(
                            SChapter.create().apply {
                                this.name = "Capítulo $numStr" + if (titleStr.isNotEmpty()) " - $titleStr" else ""
                                this.url = cleanPath
                                this.chapter_number = numStr.toFloatOrNull() ?: -1f
                            },
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        val cleanUrl = if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"
        return GET("$baseUrl$cleanUrl", headersBuilder().set("Referer", "$baseUrl/").build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val reqUrl = response.request.url.toString()

        // 1. Identify chapter ID to reach the actual reader page (/manga/leer/$chapterId)
        var chapterId = Regex("""/(?:leer|c)/(\d+)""").find(reqUrl)?.groupValues?.get(1).orEmpty()
        if (chapterId.isEmpty()) {
            val link = doc.selectFirst("a.readButton, a#leer, a[href*='/leer/'], a[href*='/c/']")?.attr("href").orEmpty()
            chapterId = Regex("""/(?:leer|c)/(\d+)""").find(link)?.groupValues?.get(1).orEmpty()
        }
        if (chapterId.isEmpty()) {
            chapterId = Regex("""manga/(?:leer|c)/(\d+)""").find(doc.html())?.groupValues?.get(1).orEmpty()
        }

        var readerDoc = doc
        var htmlContent = doc.html()

        // 2. If not already on the reader page, navigate to /manga/leer/$chapterId
        if (chapterId.isNotEmpty() && !reqUrl.contains("/leer/")) {
            val readerReq = GET("$baseUrl/manga/leer/$chapterId", headersBuilder().set("Referer", reqUrl).build())
            val readerRes = client.newCall(readerReq).execute()
            if (!readerRes.isSuccessful) {
                if (readerRes.code == 403 || readerRes.code == 503) {
                    throw Exception("Protegido por Cloudflare (HTTP ${readerRes.code}). Abre el WebView para resolver la verificación.")
                }
                throw Exception("Error HTTP ${readerRes.code} al obtener páginas")
            }
            val bodyText = readerRes.body.string()
            if (isCloudflareChallenge(bodyText)) {
                throw Exception("Protegido por Cloudflare. Abre el WebView para resolver la verificación.")
            }
            htmlContent = bodyText
            readerDoc = Jsoup.parse(bodyText, "$baseUrl/manga/leer/$chapterId")
        }

        // --- Method A: Hex encoded images (img.php?src=<hex>) ---
        val hexImages = readerDoc.select("img[data-src*='img.php?src='], img[src*='img.php?src=']")
        if (hexImages.isNotEmpty()) {
            val pages = hexImages.mapIndexedNotNull { index, el ->
                val src = el.attr("data-src").ifEmpty { el.attr("src") }
                val hex = src.substringAfter("img.php?src=").substringBefore("&")
                val decoded = decodeHex(hex)
                if (decoded.isNotBlank()) {
                    val fullUrl = if (decoded.startsWith("http")) decoded else "$baseUrl/${decoded.removePrefix("/")}"
                    Page(index, "$baseUrl/", fullUrl)
                } else null
            }
            if (pages.isNotEmpty()) return pages
        }

        // --- Method B: pUrl (double-base64 reversed with 10-char drop) ---
        val rawPUrl = readerDoc.selectFirst("script:containsData(pUrl=)")?.data()
            ?.substringAfter("pUrl=")
            ?.substringBefore(";")
            ?.trim()
            ?.removeSurrounding("'", "\"")

        if (!rawPUrl.isNullOrBlank()) {
            val decoded = decodePUrl(rawPUrl)
            if (decoded.isNotEmpty()) {
                return decoded.mapIndexed { index, imgUrl ->
                    val fullUrl = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl/${imgUrl.removePrefix("/").replace("\\", "")}"
                    Page(index, "$baseUrl/", fullUrl)
                }
            }
        }

        // --- Method C: rpics / jaja XOR decryption with "Jr54VwepF4La" ---
        val rpicsMatch = Regex("""const\s+rpics\s*=\s*([a-zA-Z0-9_]+);""").find(htmlContent)
        val rpicsVarName = rpicsMatch?.groupValues?.get(1)
        val rpicsB64 = if (rpicsVarName != null) {
            Regex("""const\s+$rpicsVarName\s*=\s*["']([^"']+)["']""").find(htmlContent)?.groupValues?.get(1)
        } else null
        val jajaB64 = Regex("""(?:const|var|let)?\s*jaja\s*=\s*["']([^"']+)["']""").find(htmlContent)?.groupValues?.get(1)

        val xorB64 = rpicsB64 ?: jajaB64
        if (!xorB64.isNullOrBlank()) {
            val decodedUrls = decodeXor(xorB64)
            if (decodedUrls.isNotEmpty()) {
                return decodedUrls.mapIndexed { index, imgUrl ->
                    val realUrl = if ("src=" in imgUrl) {
                        val hexStr = imgUrl.substringAfter("src=").substringBefore("&")
                        decodeHex(hexStr).ifEmpty { imgUrl }
                    } else imgUrl
                    val fullUrl = if (realUrl.startsWith("http")) realUrl else "$baseUrl/${realUrl.removePrefix("/").replace("\\", "")}"
                    Page(index, "$baseUrl/", fullUrl)
                }
            }
        }

        // --- Method D: pageFormat ("12345" key decryption) ---
        val pageFormatMatch = Regex("""pageFormat\s*[:=]\s*["']([^"']+)["']""").find(htmlContent)
        val pagesCountMatch = Regex("""(?:pages|totCntnts|total)\s*[:=]\s*(\d+)""").find(htmlContent)
        if (pageFormatMatch != null) {
            val pageFormatB64 = pageFormatMatch.groupValues[1]
            val template = decryptPageFormat(pageFormatB64, "12345")
            val totalPages = pagesCountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 100
            if (template.contains("{pnumber}") || template.contains("/manga/")) {
                val pages = mutableListOf<Page>()
                for (p in 1..totalPages) {
                    val url = template.replace("{pnumber}", p.toString())
                    val fullUrl = if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
                    pages.add(Page(p - 1, "$baseUrl/", fullUrl))
                }
                if (pages.isNotEmpty()) return pages
            }
        }

        // --- Method E: Fallback to direct images in reader DOM ---
        val directImages = readerDoc.select("img").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            val clean = src.substringBefore("?")
            if (clean.contains("/manga/") &&
                !clean.contains("static.kumanga.com") &&
                !clean.contains("logo.png") &&
                clean.substringAfterLast(".").lowercase() in listOf("jpg", "jpeg", "png", "webp")
            ) {
                if (src.startsWith("http")) src else "$baseUrl/${src.removePrefix("/")}"
            } else null
        }.distinct()

        if (directImages.isNotEmpty()) {
            return directImages.mapIndexed { index, url -> Page(index, "$baseUrl/", url) }
        }

        if (isCloudflareChallenge(htmlContent)) {
            throw Exception("Protegido por Cloudflare. Abre el WebView para resolver la verificación.")
        }

        throw Exception("No fue posible obtener las páginas del capítulo. Abre el WebView para verificar.")
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("Just a moment", ignoreCase = true) ||
            html.contains("Un momento", ignoreCase = true) ||
            html.contains("cf-turnstile") ||
            html.contains("challenges.cloudflare.com") ||
            html.contains("Verificación de seguridad")
    }

    private fun decodeHex(hex: String): String = try {
        hex.chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
    } catch (_: Exception) {
        ""
    }

    private fun decodePUrl(rawPUrl: String): List<String> = try {
        val firstDecode = Base64.decode(rawPUrl, Base64.DEFAULT).toString(Charsets.UTF_8)
        val reversed = firstDecode.reversed()
        val stripped = reversed.substring(10, reversed.length - 10)
        val secondDecode = Base64.decode(stripped, Base64.DEFAULT).toString(Charsets.UTF_8)
        val imageList = json.decodeFromString<List<ImageDto>>(secondDecode)
        imageList.map { it.imgURL }
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodeXor(b64Str: String): List<String> = try {
        val key = "Jr54VwepF4La"
        val rawBytes = Base64.decode(b64Str, Base64.DEFAULT)
        val decryptedChars = CharArray(rawBytes.size) { i ->
            val byteVal = rawBytes[i].toInt() and 0xFF
            val keyChar = key[i % key.length].code
            (byteVal xor keyChar).toChar()
        }
        val jsonString = String(decryptedChars)
        json.decodeFromString<List<String>>(jsonString)
    } catch (_: Exception) {
        emptyList()
    }

    private fun decryptPageFormat(pageFormatB64: String, key: String): String = try {
        val decodedStr = Base64.decode(pageFormatB64, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
        val sb = StringBuilder(decodedStr.length)
        for (i in decodedStr.indices) {
            val charCode1 = decodedStr[i].code
            val keyIdx = (i - 1 + key.length) % key.length
            val charCode2 = key[keyIdx].code
            val diff = charCode1 - charCode2
            sb.append(diff.toChar())
        }
        sb.toString()
    } catch (_: Exception) {
        ""
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
