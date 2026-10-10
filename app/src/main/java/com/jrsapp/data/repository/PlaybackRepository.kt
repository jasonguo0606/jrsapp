package com.jrsapp.data.repository

import android.util.Log
import com.jrsapp.data.model.PlaybackPage
import com.jrsapp.data.model.VideoSource
import com.jrsapp.data.parser.PlaybackPageParser
import com.jrsapp.util.redactedUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlaybackRepository(
    private val client: OkHttpClient = buildClient()
) {

    suspend fun loadPlaybackPage(pageUrl: String): Result<PlaybackPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageUrl.isNotBlank()) { "直播线路为空" }
                Log.d(TAG, "loadPlaybackPage start pageUrl=${redactedUrl(pageUrl)}")
                val html = fetchHtml(url = pageUrl, referer = pageUrl)
                PlaybackPageParser.extractPlaybackPage(html, pageUrl)
            }.onFailure {
                Log.e(TAG, "加载播放页失败: ${redactedUrl(pageUrl)} (${it.javaClass.simpleName})")
            }
        }

    suspend fun resolveVideoSources(pageUrl: String): Result<List<VideoSource>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageUrl.isNotBlank()) { "直播线路为空" }
                Log.d(TAG, "resolveVideoSources start pageUrl=${redactedUrl(pageUrl)}")
                resolveRecursively(
                    pageUrl = pageUrl,
                    referer = pageUrl,
                    depth = 0,
                    visited = mutableSetOf()
                ).distinctBy { it.url }
            }.onFailure {
                Log.e(TAG, "解析播放源失败: ${redactedUrl(pageUrl)} (${it.javaClass.simpleName})")
            }
        }

    private fun resolveRecursively(
        pageUrl: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>
    ): List<VideoSource> {
        if (depth > MAX_DEPTH || !visited.add(pageUrl)) return emptyList()

        val html = fetchHtml(url = pageUrl, referer = referer)
        Log.d(TAG, "depth=$depth fetched pageUrl=${redactedUrl(pageUrl)} htmlLen=${html.length}")
        val directSources = PlaybackPageParser.extractVideoSources(html, pageUrl)
        if (directSources.isNotEmpty()) {
            Log.d(TAG, "depth=$depth 直接解析到 ${directSources.size} 个视频源: ${redactedUrl(pageUrl)}")
            directSources.forEachIndexed { index, source ->
                Log.d(TAG, "depth=$depth direct[$index] type=${source.type} host=${runCatching { java.net.URI(source.url).host }.getOrNull()}")
            }
            return directSources
        }

        val nestedPages = PlaybackPageParser.extractNestedPageUrls(html, pageUrl)
            .filterNot { it == pageUrl }
            .distinct()

        Log.d(TAG, "depth=$depth 未命中直接视频源，嵌套页面 ${nestedPages.size} 个: ${redactedUrl(pageUrl)}")

        var firstFailure: Exception? = null
        nestedPages.forEach { nestedUrl ->
            Log.d(TAG, "depth=$depth 递归进入 nestedUrl=${redactedUrl(nestedUrl)}")
            val sources = try {
                resolveRecursively(nestedUrl, pageUrl, depth + 1, visited)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (firstFailure == null) firstFailure = error
                Log.w(TAG, "跳过失效的嵌套播放页 host=${runCatching { java.net.URI(nestedUrl).host }.getOrNull()} (${error.javaClass.simpleName})")
                emptyList()
            }
            if (sources.isNotEmpty()) {
                Log.d(TAG, "depth=$depth nestedUrl=${redactedUrl(nestedUrl)} 返回 ${sources.size} 个源")
                return sources.map { source ->
                    if (source.referer == null) source.copy(referer = nestedUrl) else source
                }
            }
        }

        firstFailure?.let { throw it }
        Log.d(TAG, "depth=$depth 没有找到可播放源 pageUrl=${redactedUrl(pageUrl)}")
        return emptyList()
    }

    private fun fetchHtml(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .build()

        Log.d(TAG, "fetchHtml request url=${redactedUrl(url)}")
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("播放页 HTTP ${response.code} (${response.request.url.host})")
            }
            response.body?.string().orEmpty()
                .also {
                    Log.d(
                        TAG,
                        "fetchHtml response url=${redactedUrl(url)} code=${response.code} bodyLen=${it.length}"
                    )
                }
        }
    }

    companion object {
        private const val TAG = "PlaybackRepository"
        private const val MAX_DEPTH = 4

        private fun buildClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val isQqApi = original.url.host.contains("qq.com", ignoreCase = true) &&
                        original.url.queryParameter("cmd") == "2"
                    val request = original.newBuilder()
                        .header(
                            "User-Agent",
                            if (isQqApi) {
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                            } else {
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            }
                        )
                        .header(
                            "Accept",
                            if (isQqApi) {
                                "*/*"
                            } else {
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                            }
                        )
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Connection", "keep-alive")
                        .header("Cache-Control", "no-cache")
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
    }
}
