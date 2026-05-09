package com.jrsapp.data.repository

import android.util.Log
import com.jrsapp.data.model.Match
import com.jrsapp.data.parser.MatchParser
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

class MatchRepository {

    private val client = buildClient()

    suspend fun fetchAllMatches(): Result<List<Match>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, html) = fetch(MATCH_LIST_URL)
                Log.d(TAG, "HTTP状态码: $code  HTML长度: ${html.length}")
                Log.d(TAG, "含match-item: ${html.contains("match-item")}  含steam: ${html.contains("steam")}")
                Log.d(TAG, "HTML前1200字符:\n${html.take(1200)}")
                MatchParser.parseMatches(html)
            }.onFailure {
                Log.e(TAG, "获取比赛列表失败", it)
            }
        }

    private fun fetch(url: String): Pair<Int, String> {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            resp.code to body
        }
    }

    companion object {
        private const val TAG = "MatchRepository"
        const val BASE_URL = "https://m.jrskk.com"
        private const val MATCH_LIST_URL = "https://m.jrskk.com"

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
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Connection", "keep-alive")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Cache-Control", "no-cache")
                        .build()
                    chain.proceed(req)
                }
                .build()
        }
    }
}
