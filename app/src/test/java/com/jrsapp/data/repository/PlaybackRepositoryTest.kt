package com.jrsapp.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackRepositoryTest {
    @Test
    fun triesNextNestedPageAfterHttpFailure() = runBlocking {
        val encodedMedia = "j3BEKMHGQRVGKabdikSiS+J1S3R0rTWlxFfi1GTxc2ng+qdJrFvj1ZpczE5uKDXsA/7ng6ArvFeKUAVcAEWRdg=="
        val requestedPaths = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath
            requestedPaths += path
            val (status, body) = when (path) {
                "/play/steam123.html" -> 200 to """
                    <iframe src="/play/kbs.html?id=bad"></iframe>
                    <iframe src="/play/pao/?id=123"></iframe>
                """.trimIndent()
                "/play/kbs.html" -> 404 to "missing"
                "/play/pao/" -> 200 to """<script>var encodedStr = "$encodedMedia";</script>"""
                else -> error("Unexpected request: $path")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody("text/html".toMediaType()))
                .build()
        }
        val repository = PlaybackRepository(OkHttpClient.Builder().addInterceptor(interceptor).build())

        val sources = repository.resolveVideoSources(
            "http://m.sportsteam53.com/play/steam123.html"
        ).getOrThrow()

        assertEquals("https://video.example/live/test.m3u8?token=sample", sources.single().url)
        assertEquals(listOf("/play/steam123.html", "/play/kbs.html", "/play/pao/"), requestedPaths)
    }
}
