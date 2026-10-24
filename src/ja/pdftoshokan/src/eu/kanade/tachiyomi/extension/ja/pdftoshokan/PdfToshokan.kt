package eu.kanade.tachiyomi.extension.ja.pdftoshokan

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@Source
abstract class PdfToshokan : KeiSource() {

    private val pdfCache by lazy { PdfCache() }

    override fun OkHttpClient.Builder.configureClient() = apply {
        readTimeout(2, TimeUnit.MINUTES)
        addInterceptor(PdfPageInterceptor(pdfCache))
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(listingUrl("popular", page))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing(listingUrl(null, page))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = listingUrl(null, page).newBuilder()
            .addQueryParameter("s", query)
            .build()
        return parseListing(url)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() in NON_POST_PATHS) return null
        val manga = SManga.create().apply { this.url = url.encodedPath }
        return mangaDetailsParse(client.get(getMangaUrl(manga)).asJsoup(), manga.url)
    }

    private fun listingUrl(section: String?, page: Int): HttpUrl = baseUrl.toHttpUrl().newBuilder().apply {
        section?.let { addPathSegment(it) }
        if (page > 1) addPathSegment("page").addPathSegment(page.toString())
        addPathSegment("")
    }.build()

    private suspend fun parseListing(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select(LIST_ITEM_SELECTOR)
            .mapNotNull(::mangaFromElement)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.select("a[href]").firstOrNull { isPostUrl(it.absUrl("href")) } ?: return null
        val img = element.selectFirst("img")
        val title = element.selectFirst("h1, h2, h3, .title, .entry-title")?.text()?.takeIf { it.isNotEmpty() }
            ?: link.attrOrNull("title")
            ?: img?.attrOrNull("alt")
            ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            this.title = title
            thumbnail_url = img?.imageUrl()
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    private fun isPostUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNullSafe() ?: return false
        if (httpUrl.host != baseUrl.toHttpUrl().host) return false
        val first = httpUrl.pathSegments.firstOrNull { it.isNotEmpty() } ?: return false
        return first !in NON_POST_PATHS
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "PDF"
            date_upload = dateFormat.tryParse(
                document.selectFirst("meta[property=article:published_time]")?.attr("content"),
            )
        }
        return SMangaUpdate(mangaDetailsParse(document, manga.url), listOf(chapter))
    }

    private fun mangaDetailsParse(document: Document, url: String) = SManga.create().apply {
        this.url = url
        title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")!!.attr("content")
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attrOrNull("content")
            ?: document.selectFirst("article img, .entry-content img")?.imageUrl()
        description = document.selectFirst("meta[property=og:description]")?.attrOrNull("content")
        genre = document.select("a[rel~=tag], a[rel~=category]")
            .map { it.text() }
            .distinct()
            .joinToString()
            .ifEmpty { null }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val pdfUrl = findPdfUrl(document) ?: return emptyList()

        val pageCount = withContext(Dispatchers.IO) {
            pdfCache.getOrDownload(pdfUrl) { client.newCall(PdfPageInterceptor.pdfRequest(pdfUrl, headers)).execute() }
                .let(PdfCache::pageCount)
        }

        return List(pageCount) { index ->
            Page(index, imageUrl = PdfPageInterceptor.pageUrl(pdfUrl, index))
        }
    }

    private fun findPdfUrl(document: Document): String? {
        document.select(PDF_LINK_SELECTOR).forEach { element ->
            val raw = element.absUrl("href").ifEmpty { element.absUrl("src") }.ifEmpty { element.absUrl("data") }
            if (raw.isEmpty()) return@forEach
            // pdf.js viewers carry the real document in the `file` query parameter
            val viewerFile = raw.toHttpUrlOrNullSafe()?.queryParameter("file")
            return if (viewerFile != null) document.location().toHttpUrl().resolve(viewerFile).toString() else raw
        }
        return PDF_URL_REGEX.find(document.html())?.value?.replace("\\/", "/")
    }

    private fun Element.imageUrl(): String? = attrOrNull("data-src")?.let { absUrl("data-src") }
        ?: attrOrNull("data-lazy-src")?.let { absUrl("data-lazy-src") }
        ?: absUrl("src").ifEmpty { null }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    companion object {
        private const val LIST_ITEM_SELECTOR = "article, .post, .item, li.entry"
        private const val NEXT_PAGE_SELECTOR = "a.next, a[rel=next], link[rel=next], .nav-links .next, .pagination .next"
        private const val PDF_LINK_SELECTOR =
            "a[href*=/pdf/], a[href*=.pdf], iframe[src*=.pdf], iframe[src*=/pdf/], embed[src*=.pdf], object[data*=.pdf], iframe[src*=file=]"
        private val PDF_URL_REGEX = Regex("""https?:(?:\\?/){2}[^"'\s<>]+?\.pdf(?:\?[^"'\s<>]*)?""")
        private val NON_POST_PATHS = setOf(
            "page", "search", "popular", "week", "month", "listed", "pdf",
            "tag", "category", "author", "wp-content", "wp-admin", "wp-includes", "feed",
        )
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    }
}
