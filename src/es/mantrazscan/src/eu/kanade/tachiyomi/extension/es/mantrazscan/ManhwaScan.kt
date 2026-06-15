package eu.kanade.tachiyomi.extension.es.mantrazscan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ManhwaScan : ParsedHttpSource() {

    override val name = "Manhwa Scan"

    override val baseUrl = "https://manhwaxcan.com"

    override val lang = "es"

    override val supportsLatest = true

    override val id: Long = 7172992930543738693

    private val cdnBaseUrl = "https://manhwascan.cv"

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/explorar/page/$page/", headers)

    override fun popularMangaSelector() = "div.series-grid > div.s-card"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val linkEl = element.selectFirst("a.s-card-title")!!
        title = linkEl.text()
        setUrlWithoutDomain(linkEl.attr("href"))
        thumbnail_url = element.selectFirst("div.s-card-img img")?.attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "div.pager > a.pager-btn"

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = "div.series-grid > div.s-card"

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

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

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Details ==============================
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.series-title")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.series-img img")?.attr("abs:src")
        description = document.selectFirst("div.summary")?.text()
        genre = document.select("a[href*=/genero/]").joinToString(", ") { it.text().trim() }
        status = SManga.UNKNOWN
    }

    // ============================= Chapters ==============================
    override fun chapterListSelector() = "a.ch-row"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.ch-num")?.text()?.trim() ?: element.text().trim()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .reversed()
    }

    // =============================== Pages ===============================
    override fun pageListParse(document: Document): List<Page> {
        return document.select("img[src*=wp-content/uploads/WP-manga]").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
}
