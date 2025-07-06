package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestMangaParser(context: MangaLoaderContext) : MangaParser(context, MangaSource.WESTMANGA) {

    override val configKeyDomain = ConfigKey.Domain("westmanga.me")
    private val apiSuffix = "westmanga.me/api"
    private val pageSize = 20

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override suspend fun getList(
        offset: Int,
        query: String?,
        tags: Set<MangaTag>?,
        sortOrder: SortOrder,
    ): List<Manga> {
        val page = (offset / pageSize) + 1
        val url = buildString {
            append("https://")
            append(apiSuffix)
            append("/contents")
            append("?page=")
            append(page)
            append("&per_page=")
            append(pageSize)
            append("&type=Comic")
            append("&orderBy=")
            append(
                when (sortOrder) {
                    SortOrder.POPULARITY -> "Popular"
                    SortOrder.UPDATED -> "Update"
                    else -> "Update"
                },
            )
            if (!query.isNullOrEmpty()) {
                append("&q=")
                append(query.urlEncoded())
            }
            tags?.let { tagSet ->
                tagSet.forEach { tag ->
                    append("&genre[]=")
                    append(tag.key)
                }
            }
        }
        val headers = apiHeaders(url)
        val json = webClient.httpGet(url, headers).parseJson()
        return json.getJSONArray("data").mapJSON { jo ->
            Manga(
                id = generateUid(jo.getString("slug")),
                title = jo.getString("title"),
                altTitle = null,
                url = "/comic/${jo.getString("slug")}",
                publicUrl = "https://${domain}/comic/${jo.getString("slug")}",
                coverUrl = jo.getStringOrNull("cover")?.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                author = null,
                state = null,
                tags = emptySet(),
                description = null,
                isNsfw = false,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/comic/").removeSuffix("/")
        val url = "https://$apiSuffix/comic/$slug"
        val headers = apiHeaders(url)
        val json = webClient.httpGet(url, headers).parseJson()
        val data = json.getJSONObject("data")
        return manga.copy(
            title = data.getString("title"),
            altTitle = data.getStringOrNull("alternative_name"),
            author = data.getStringOrNull("author"),
            coverUrl = data.getStringOrNull("cover")?.toAbsoluteUrl(domain),
            description = data.getStringOrNull("synopsis"),
            state = when (data.getStringOrNull("status")) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                else -> null
            },
            tags = data.optJSONArray("genres")?.mapJSONToSet { genre ->
                MangaTag(
                    key = genre.getString("id"),
                    title = genre.getString("name"),
                    source = source,
                )
            } ?: emptySet(),
            chapters = data.getJSONArray("chapters").mapJSON { chapter ->
                MangaChapter(
                    id = generateUid(chapter.getString("slug")),
                    name = "Chapter ${chapter.getString("number")}",
                    number = chapter.getString("number").toFloatOrNull() ?: 0f,
                    url = "/v/${chapter.getString("slug")}",
                    scanlator = null,
                    uploadDate = parseDate(chapter.getJSONObject("updated_at").getLong("time")),
                    branch = null,
                    source = source,
                )
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.substringAfter("/v/").removeSuffix("/")
        val url = "https://$apiSuffix/v/$slug"
        val headers = apiHeaders(url)
        val json = webClient.httpGet(url, headers).parseJson()
        val data = json.getJSONObject("data")
        return data.getJSONArray("images").mapIndexed { index, value ->
            val imageUrl = value.toString().removeSurrounding("\"")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
                number = index,
            )
        }
    }

    override suspend fun getTags(): Set<MangaTag> {
        val url = "https://$apiSuffix/genres"
        val headers = apiHeaders(url)
        return try {
            val json = webClient.httpGet(url, headers).parseJson()
            json.getJSONArray("data").mapJSONToSet { genre ->
                MangaTag(
                    key = genre.getString("id"),
                    title = genre.getString("name"),
                    source = source,
                )
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun apiHeaders(requestUrl: String): Headers {
        val base = "https://$apiSuffix"
        val pathAndQuery = requestUrl.substringAfter(base).ifEmpty { "/" }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val message = "wm-api-request"
        val key = timestamp + "GET" + pathAndQuery + ACCESS_KEY + SECRET_KEY
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val signature = hash.joinToString("") { "%02x".format(it) }
        return Headers.Builder()
            .add("x-wm-request-time", timestamp)
            .add("x-wm-accses-key", ACCESS_KEY)
            .add("x-wm-request-signature", signature)
            .build()
    }

    private fun parseDate(time: Long): Long {
        return time * 1000
    }

    companion object {
        private const val ACCESS_KEY = "WM_WEB_FRONT_END"
        private const val SECRET_KEY = "xxxoidj"
    }
}