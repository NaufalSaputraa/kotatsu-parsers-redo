package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("XOMANGA", "XoManga", "en", ContentType.MANGA)
internal class XoManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.XOMANGA, pageSize = 100) {

	override val configKeyDomain = ConfigKey.Domain("xomanga.site", "www.xomanga.site")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page > 1) return emptyList()

		val items = fetchIndexItems()
		val query = filter.query?.trim().orEmpty()
		val filtered = when {
			query.isNotEmpty() -> {
				val normalizedQuery = normalize(query)
				items.filter { it.matchesQuery(normalizedQuery) }
			}

			order == SortOrder.POPULARITY -> {
				val exclusiveTitles = fetchExclusiveTitles()
				items.filter { it.isExclusive(exclusiveTitles) }
			}

			else -> items
		}

		return filtered.mapNotNull { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val root = webClient.httpGet("https://$domain/manga/$slug/details.json").parseJsonSafe()
		val details = root.optJSONObject("manga")
			?: root.optJSONObject("data")
			?: root

		val title = details.optString("title").ifBlank { manga.title }
		val description = details.optString("description")
			.ifBlank { details.optString("summary") }
			.nullIfEmpty()
		val statusRaw = details.optString("status").ifBlank { details.optString("state") }
		val state = parseState(statusRaw) ?: manga.state

		val cover = listOf(
			details.optString("cover").nullIfEmpty(),
			details.optString("thumbnail").nullIfEmpty(),
			details.optString("image").nullIfEmpty(),
		).firstOrNull { !it.isNullOrBlank() }?.toAbsoluteUrl(domain)
			?: manga.coverUrl

		val authors = linkedSetOf<String>().apply {
			addAll(details.optString("author").splitAndTrim())
			addAll(details.optString("artist").splitAndTrim())
		}

		val tags = parseTags(details.opt("genres"), details.opt("tags"))

		val chaptersJson = root.optJSONArray("chaptersList")
			?: details.optJSONArray("chaptersList")
			?: root.optJSONArray("chapters")
			?: details.optJSONArray("chapters")
			?: JSONArray()

		val chapters = ArrayList<MangaChapter>(chaptersJson.length())
		for (i in 0 until chaptersJson.length()) {
			val chapter = chaptersJson.optJSONObject(i) ?: continue
			val chapterNumRaw = chapter.opt("chapter")?.toString()?.nullIfEmpty()
				?: chapter.opt("number")?.toString()?.nullIfEmpty()
				?: chapter.opt("chapterNumber")?.toString()?.nullIfEmpty()
				?: chapter.opt("ch")?.toString()?.nullIfEmpty()
				?: continue
			val chapterNum = chapterNumRaw.toFloatOrNull() ?: continue
			val chapterName = chapter.optString("title").ifBlank { chapter.optString("name") }.trim()
			val titleText = if (chapterName.isBlank()) {
				"Chapter ${chapterNum.toSimpleString()}"
			} else if (chapterName.contains(chapterNum.toSimpleString())) {
				chapterName
			} else {
				"Chapter ${chapterNum.toSimpleString()} - $chapterName"
			}
			val dateText = chapter.optString("date")
				.ifBlank { chapter.optString("created_at") }
				.ifBlank { chapter.optString("updated_at") }
			chapters += MangaChapter(
				id = generateUid("$slug#$chapterNumRaw"),
				title = titleText,
				number = chapterNum,
				volume = 0,
				url = "$slug#$chapterNumRaw",
				scanlator = null,
				uploadDate = parseDate(dateText),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			publicUrl = "https://$domain/details.html?id=$slug",
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			state = state,
			authors = if (authors.isEmpty()) manga.authors else authors,
			tags = if (tags.isEmpty()) manga.tags else tags,
			chapters = chapters.sortedBy { it.number },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val split = chapter.url.split('#', limit = 2)
		val slug = split.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return emptyList()
		val chapterNum = split.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return emptyList()

		val root = webClient.httpGet("https://$domain/manga/$slug/chapters/$chapterNum.json").parseJsonSafe()
		val images = root.optJSONArray("images")
			?: root.optJSONObject("data")?.optJSONArray("images")
			?: JSONArray()

		val pages = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val imageUrl = when (val item = images.opt(i)) {
				is String -> item.nullIfEmpty()
				is JSONObject -> item.optString("url")
					.ifBlank { item.optString("src") }
					.ifBlank { item.optString("image") }
					.nullIfEmpty()

				else -> null
			}?.toAbsoluteUrl(domain) ?: continue

			pages += MangaPage(
				id = generateUid("$slug#$chapterNum:$i"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		return pages
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private suspend fun fetchExclusiveTitles(): Set<String> {
		val html = webClient.httpGet("https://$domain/our-works.html").parseRaw()
		val block = EXCLUSIVE_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
		return QUOTED_REGEX.findAll(block)
			.map { normalize(it.groupValues[1]) }
			.filter { it.isNotBlank() }
			.toSet()
	}

	private suspend fun fetchIndexItems(): List<JSONObject> {
		val raw = webClient.httpGet("https://$domain/index.json").parseRaw().trim()
		if (raw.isEmpty()) return emptyList()
		val array = if (raw.startsWith("[")) {
			runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
		} else {
			val obj = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
			obj.optJSONArray("latest")
				?: obj.optJSONObject("data")?.optJSONArray("latest")
				?: obj.optJSONArray("data")
				?: JSONArray()
		}
		return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i) }
	}

	private fun JSONObject.toManga(): Manga? {
		val slug = extractSlug(this) ?: return null
		val title = optString("title").ifBlank { optString("name") }.ifBlank { slug }
		val description = optString("description").ifBlank { optString("summary") }.nullIfEmpty()
		val cover = listOf(
			optString("cover").nullIfEmpty(),
			optString("thumbnail").nullIfEmpty(),
			optString("image").nullIfEmpty(),
		).firstOrNull { !it.isNullOrBlank() }?.toAbsoluteUrl(domain)

		val authors = linkedSetOf<String>().apply {
			addAll(optString("author").splitAndTrim())
			addAll(optString("artist").splitAndTrim())
		}

		return Manga(
			id = generateUid(slug),
			title = title,
			altTitles = setOfNotNull(optString("altTitle").nullIfEmpty()),
			url = slug,
			publicUrl = "https://$domain/details.html?id=$slug",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = cover,
			tags = emptySet(),
			state = parseState(optString("status").ifBlank { optString("state") }),
			authors = authors,
			largeCoverUrl = cover,
			description = description,
			chapters = null,
			source = source,
		)
	}

	private fun JSONObject.matchesQuery(normalizedQuery: String): Boolean {
		if (normalizedQuery.isBlank()) return true
		val haystack = buildString {
			append(optString("title"))
			append(' ')
			append(optString("name"))
			append(' ')
			append(optString("author"))
			append(' ')
			append(optString("altTitle"))
		}
		return normalize(haystack).contains(normalizedQuery)
	}

	private fun JSONObject.isExclusive(exclusiveTitles: Set<String>): Boolean {
		if (exclusiveTitles.isEmpty()) return false
		return normalize(optString("title").ifBlank { optString("name") }) in exclusiveTitles
	}

	private fun extractSlug(item: JSONObject): String? {
		val link = item.optString("link").nullIfEmpty()
		if (!link.isNullOrBlank()) {
			val idParam = link.substringAfter("id=", "").substringBefore('&').trim()
			if (idParam.isNotEmpty()) return idParam
		}
		val candidates = listOf(
			item.optString("slug").nullIfEmpty(),
			item.optString("id").nullIfEmpty(),
			item.optString("url").nullIfEmpty(),
		)
		for (candidate in candidates) {
			if (candidate.isNullOrBlank()) continue
			val cleaned = candidate
				.substringBefore('?')
				.substringAfterLast('/')
				.substringAfter("id=", candidate)
				.trim()
				.trim('/')
			if (cleaned.isNotEmpty()) return cleaned
		}
		return null
	}

	private fun parseState(raw: String?): MangaState? = when (raw?.lowercase(Locale.ROOT)?.trim()) {
		"ongoing", "on-going" -> MangaState.ONGOING
		"completed", "complete" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseTags(vararg values: Any?): Set<MangaTag> {
		val result = linkedSetOf<MangaTag>()
		values.forEach { value ->
			when (value) {
				is JSONArray -> {
					for (i in 0 until value.length()) {
						when (val item = value.opt(i)) {
							is String -> item.splitAndTrim().forEach { tag ->
								result += MangaTag(tag.toTagKey(), tag, source)
							}

							is JSONObject -> {
								val title = item.optString("name")
									.ifBlank { item.optString("title") }
									.nullIfEmpty() ?: continue
								val key = item.optString("id").nullIfEmpty()
									?: item.optString("slug").nullIfEmpty()
									?: title.toTagKey()
								result += MangaTag(key, title, source)
							}
						}
					}
				}

				is String -> value.splitAndTrim().forEach { tag ->
					result += MangaTag(tag.toTagKey(), tag, source)
				}
			}
		}
		return result
	}

	private fun String.splitAndTrim(): Set<String> = split(',', ';', '|')
		.map { it.trim() }
		.filter { it.isNotEmpty() }
		.toSet()

	private fun String.toTagKey(): String = lowercase(Locale.ROOT)
		.replace("[^a-z0-9]+".toRegex(), "-")
		.trim('-')

	private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
		.trim()
		.replace(WHITESPACE_REGEX, " ")

	private fun Float.toSimpleString(): String = if (this % 1f == 0f) toInt().toString() else toString()

	private fun parseDate(value: String?): Long {
		val raw = value?.trim().orEmpty()
		if (raw.isEmpty()) return 0L
		val patterns = listOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
			"yyyy-MM-dd'T'HH:mm:ss'Z'",
			"yyyy-MM-dd HH:mm:ss",
			"yyyy-MM-dd",
		)
		for (pattern in patterns) {
			val parsed = SimpleDateFormat(pattern, Locale.US).parseSafe(raw)
			if (parsed > 0L) return parsed
		}
		return 0L
	}

	private fun okhttp3.Response.parseJsonSafe(): JSONObject {
		return runCatching { parseJson() }.getOrElse {
			val raw = parseRaw()
			runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
		}
	}

	private companion object {
		private val EXCLUSIVE_REGEX = Regex("""myExclusiveWorksTitles\s*=\s*\[([^]]+)]""", RegexOption.DOT_MATCHES_ALL)
		private val QUOTED_REGEX = Regex("""["']([^"'\n]+)["']""")
		private val WHITESPACE_REGEX = Regex("""\s+""")
	}
}
