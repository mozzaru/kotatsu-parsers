package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@Broken
@MangaSourceParser("MANGAPURE", "MangaPure", "en")
internal class MangaPure(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGAPURE, "mangapure.net") {
    override val tagPrefix = "mangas/"
    override val listUrl = "latest-manga/"
    override val datePattern = "MMMM d, HH:mm"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return super.getFilterOptions().copy(
            availableStates = emptySet(),
            availableContentRating = emptySet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search?s=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page.toString())
                    append("&post_type=wp-manga")
                }

                else -> {

                    val tag = filter.tags.oneOrThrowIfMany()
                    if (filter.tags.isNotEmpty()) {
                        append("/$tagPrefix")
                        append(tag?.key.orEmpty())
                        append("?orderby=")
                        when (order) {
                            SortOrder.POPULARITY -> append("2")
                            SortOrder.UPDATED -> append("3")
                            else -> append("3")
                        }
                        append("&page=")
                        append(page.toString())
                    } else {
                        when (order) {
                            SortOrder.POPULARITY -> append("/popular-manga")
                            SortOrder.UPDATED -> append("/latest-manga")
                            else -> append("/latest-manga")
                        }
                        append("?page=")
                        append(page.toString())
                    }
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("div.row.c-tabs-item__content").ifEmpty {
            doc.select("div.page-item-detail.manga")
        }.map { div ->
            val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
            val summary = div.selectFirst(".tab-summary") ?: div.selectFirst(".item-summary")
            val author = summary?.selectFirst(".mg_author")?.selectFirst("a")?.ownText()
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(div.host ?: domain),
                coverUrl = div.selectFirst("img")?.src(),
                title = (summary?.selectFirst("h3") ?: summary?.selectFirst("h4"))?.text().orEmpty(),
                altTitles = emptySet(),
                rating = div.selectFirst("span.total_votes")?.ownText()?.toFloatOrNull()?.div(5f) ?: -1f,
                tags = summary?.selectFirst(".mg_genres")?.select("a")?.mapNotNullToSet { a ->
                    MangaTag(
                        key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
                        title = a.text().ifEmpty { return@mapNotNullToSet null }.toTitleCase(),
                        source = source,
                    )
                }.orEmpty(),
                authors = setOfNotNull(author),
                state = when (summary?.selectFirst(".mg_status")?.selectFirst(".summary-content")?.ownText()
                    ?.lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {


        val mangaId = document.select("div[id^=manga-chapters-holder]").attr("data-id")

        val doc = webClient.httpGet("https://$domain/ajax-list-chapter?mangaID=$mangaId").parseHtml()

        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

        return doc.select(selectChapter).mapChapters(reversed = true) { i, li ->
            val a = li.selectFirst("a")
            val href = a?.attrAsRelativeUrlOrNull("href") ?: li.parseFailed("Link is missing")
            val link = href + stylePage
            MangaChapter(
                id = generateUid(href),
                url = link,
                title = a.ownText(),
                number = i + 1f,
                volume = 0,
                branch = null,
                uploadDate = parseChapterDate(
                    dateFormat,
                    li.selectFirst(selectDate)?.text(),
                ),
                scanlator = null,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val urlarray = doc.select("p#arraydata").text().split(",").toTypedArray()
        return urlarray.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
