package com.jrsapp.data.repository

import android.util.Log
import com.jrsapp.data.model.PlaybackPage
import com.jrsapp.data.model.VideoSource
import com.jrsapp.data.parser.PlaybackPageParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PlaybackRepository(
    private val client: OkHttpClient = buildClient()
) {

    suspend fun loadPlaybackPage(pageUrl: String): Result<PlaybackPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageUrl.isNotBlank()) { "直播线路为空" }
                Log.d(TAG, "loadPlaybackPage start pageUrl=$pageUrl")
                val html = fetchHtml(url = pageUrl, referer = pageUrl)
                PlaybackPageParser.extractPlaybackPage(html, pageUrl)
            }.onFailure {
                Log.e(TAG, "加载播放页失败: $pageUrl", it)
            }
        }

    suspend fun resolveVideoSources(pageUrl: String): Result<List<VideoSource>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageUrl.isNotBlank()) { "直播线路为空" }
                Log.d(TAG, "resolveVideoSources start pageUrl=$pageUrl")
                resolveRecursively(pageUrl = pageUrl, referer = pageUrl, depth = 0).distinctBy { it.url }
            }.onFailure {
                Log.e(TAG, "解析播放源失败: $pageUrl", it)
            }
        }

    private fun resolveRecursively(pageUrl: String, referer: String, depth: Int): List<VideoSource> {
        if (depth > MAX_DEPTH) return emptyList()

        val html = fetchHtml(url = pageUrl, referer = referer)
        Log.d(TAG, "depth=$depth fetched pageUrl=$pageUrl referer=$referer htmlLen=${html.length} head=${html.take(500)}")
        val directSources = PlaybackPageParser.extractVideoSources(html, pageUrl)
        if (directSources.isNotEmpty()) {
            Log.d(TAG, "depth=$depth 直接解析到 ${directSources.size} 个视频源: $pageUrl")
            directSources.forEachIndexed { index, source ->
                Log.d(TAG, "depth=$depth direct[$index] url=${source.url} type=${source.type} referer=${source.referer}")
            }
            return directSources
        }

        val nestedPages = PlaybackPageParser.extractNestedPageUrls(html, pageUrl)
            .filterNot { it == pageUrl }
            .distinct()

        Log.d(TAG, "depth=$depth 未命中直接视频源，嵌套页面 ${nestedPages.size} 个: $pageUrl -> $nestedPages")

        nestedPages.forEach { nestedUrl ->
            Log.d(TAG, "depth=$depth 递归进入 nestedUrl=$nestedUrl from=$pageUrl")
            val sources = resolveRecursively(pageUrl = nestedUrl, referer = pageUrl, depth = depth + 1)
            if (sources.isNotEmpty()) {
                Log.d(TAG, "depth=$depth nestedUrl=$nestedUrl 返回 ${sources.size} 个源")
                return sources.map { it.copy(referer = nestedUrl) }
            }
        }

        Log.d(TAG, "depth=$depth 没有找到可播放源 pageUrl=$pageUrl")
        return emptyList()
    }

    private fun fetchHtml(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .build()

        Log.d(TAG, "fetchHtml request url=$url referer=$referer")
        return client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
                .also {
                    Log.d(
                        TAG,
                        "fetchHtml response url=$url referer=$referer code=${response.code} finalUrl=${response.request.url} bodyLen=${it.length}"
                    )
                }
        }
    }

    companion object {
        private const val TAG = "PlaybackRepository"
        private const val MAX_DEPTH = 4

        private fun buildClient(): OkHttpClient {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
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
