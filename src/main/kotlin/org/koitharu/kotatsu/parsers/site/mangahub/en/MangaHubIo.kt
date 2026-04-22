package org.koitharu.kotatsu.parsers.site.mangahub.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangahub.MangaHubParser

@MangaSourceParser("MANGAHUB_IO", "MangaHub", "en")
internal class MangaHubIo(context: MangaLoaderContext) :
	MangaHubParser(context, MangaParserSource.MANGAHUB_IO, "mangahub.io", "m01")
