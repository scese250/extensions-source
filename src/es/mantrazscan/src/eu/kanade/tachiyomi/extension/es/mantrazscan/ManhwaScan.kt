package eu.kanade.tachiyomi.extension.es.mantrazscan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class ManhwaScan : HttpSource() {

    override val name = "Manhwa Scan"

    override val baseUrl = "https://manhwaxcan.com"

    override val lang = "es"

    override val supportsLatest = true

    override val id: Long = 7172992930543738693

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/explorar/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.series-grid > div.s-card").map { element ->
            SManga.create().apply {
                val linkEl = element.selectFirst("a.s-card-title")!!
                title = linkEl.text()
                setUrlWithoutDomain(linkEl.attr("href"))
                thumbnail_url = element.selectFirst("div.s-card-img img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("div.pager > a.pager-btn") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.series-grid > div.s-card").map { element ->
            SManga.create().apply {
                val linkEl = element.selectFirst("a.s-card-title")!!
                title = linkEl.text()
                setUrlWithoutDomain(linkEl.attr("href"))
                thumbnail_url = element.selectFirst("div.s-card-img img")?.attr("abs:src")
            }
        }
        return MangasPage(mangas, false)
    }

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/explorar/".toHttpUrl().newBuilder()
        if (page > 1) {
            url.addPathSegment("page")
            url.addPathSegment("$page")
            url.addPathSegment("")
        }
        if (query.isNotBlank()) {
            url.addQueryParameter("q", query.trim())
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Details ==============================
    override fun getMangaUrl(manga: SManga): String = "$baseUrl${migrateUrl(manga.url, isManga = true)}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${migrateUrl(manga.url, isManga = true)}", headers)

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("h1.series-title")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.series-img img")?.attr("abs:src")
        description = document.selectFirst("div.summary")?.text()
        genre = document.select("a[href*=/genero/]").joinToString(", ") { it.text().trim() }
        status = SManga.UNKNOWN
    }

    // ============================= Chapters ==============================
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${migrateUrl(chapter.url, isManga = false)}"

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl${migrateUrl(manga.url, isManga = true)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a.ch-row").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.selectFirst("span.ch-num")?.text()?.trim() ?: element.text().trim()
            }
        }.reversed()
    }

    // =============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${migrateUrl(chapter.url, isManga = false)}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img[src*=wp-content/uploads/WP-manga]").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================
    private fun migrateUrl(url: String, isManga: Boolean): String {
        if (!url.contains("#")) return url
        val slug = url.substringAfter("#")
        return if (isManga) "/manga/$slug/" else "/manga/$slug"
    }
}

