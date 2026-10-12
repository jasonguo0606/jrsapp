package com.jrsapp.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackPageParserTest {
    private val pageUrl = "http://m.sportsteam53.com/play/steam823839.html"
    private val encodedMedia = "j3BEKMHGQRVGKabdikSiS+J1S3R0rTWlxFfi1GTxc2ng+qdJrFvj1ZpczE5uKDXsA/7ng6ArvFeKUAVcAEWRdg=="
    private val expectedMedia = "https://video.example/live/test.m3u8?token=sample"

    @Test
    fun keepsOnlyNonCommentarySubLines() {
        val html = """
            <a data-play="/play/sm.html?id=412">主播解说①</a>
            <a data-play="/play/wen/?id=abc">高清直播③</a>
            <a data-play="/play/pao/?id=123">高清直播④</a>
        """.trimIndent()

        val page = PlaybackPageParser.extractPlaybackPage(html, pageUrl)

        assertEquals(listOf("高清直播③", "高清直播④"), page.subLines.map { it.label })
        assertTrue(page.hasFilteredCommentary)
    }

    @Test
    fun keepsCurrentNbaChineseHdPaths() {
        val html = """
            <a href="javascript:void(0)" data-play="/play/sm.html?id=1">主播解说①</a>
            <a href="javascript:void(0)" data-play="/play/vxt/?id=2">中文高清 Q ⑤</a>
            <a href="javascript:void(0)" data-play="/play/kbs/?id=3">中文高清 Q ⑥</a>
            <a href="javascript:void(0)" data-play="/play/mgxl/?id=4">中文高清 M⑦</a>
            <a href="javascript:void(0)" data-play="/play/pao/?id=5">高清直播⑧</a>
        """.trimIndent()

        val page = PlaybackPageParser.extractPlaybackPage(html, pageUrl)

        assertEquals(
            listOf("中文高清 Q ⑤", "中文高清 Q ⑥", "中文高清 M⑦", "高清直播⑧"),
            page.subLines.map { it.label }
        )
        assertTrue(page.hasFilteredCommentary)
    }

    @Test
    fun doesNotUseCommentaryIframeAsFallback() {
        val html = """
            <iframe src="/play/sm.html?id=412"></iframe>
            <a data-play="/play/sm.html?id=412">主播解说①</a>
        """.trimIndent()

        val page = PlaybackPageParser.extractPlaybackPage(html, pageUrl)

        assertTrue(page.subLines.isEmpty())
        assertTrue(page.hasFilteredCommentary)
    }

    @Test
    fun decryptsEncodedHighDefinitionPageFormats() {
        val html = """<script>var encodedStr = "$encodedMedia";</script>"""

        for (path in listOf("wen/?id=abc", "pao/?id=123", "kbs/?id=456", "mgxl/?id=789")) {
            val sources = PlaybackPageParser.extractVideoSources(
                html,
                "http://m.sportsteam53.com/play/$path"
            )
            assertEquals(path, expectedMedia, sources.single().url)
        }
    }

    @Test
    fun decryptsUrlEncodedPapsIframeFromQLine() {
        val encodedId = java.net.URLEncoder.encode(encodedMedia, Charsets.UTF_8.name())
        val html = """<iframe src="https://demo.songzenet.com/paps.html?id=$encodedId"></iframe>"""

        val sources = PlaybackPageParser.extractVideoSources(
            html,
            "http://m.sportsteam53.com/play/vxt/?id=123"
        )

        assertEquals(expectedMedia, sources.single().url)
    }
}
