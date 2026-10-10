package com.jrsapp.data.repository

import android.util.Log
import com.jrsapp.data.model.Match
import com.jrsapp.data.parser.MatchParser
import com.jrsapp.data.parser.MatchScriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

class MatchRepository {

    private val client = buildClient()

    suspend fun fetchAllMatches(domain: String = PRIMARY_DOMAIN): Result<List<Match>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = buildBaseUrl(domain)
                val (code, html) = fetch(baseUrl)
                Log.d(TAG, "domain=$domain HTTP状态码: $code  HTML长度: ${html.length}")
                Log.d(TAG, "含match-item: ${html.contains("match-item")}  含steam: ${html.contains("steam")}")
                val matches = MatchParser.parseMatches(html, baseUrl)
                if (matches.isNotEmpty()) return@runCatching matches

                val scriptUrl = Jsoup.parse(html, baseUrl).select("script[src]")
                    .map { it.attr("abs:src") }
                    .firstOrNull { it.substringBefore('?').endsWith("/index.js") }
                    ?: return@runCatching matches
                val (_, script) = fetch(scriptUrl)
                MatchParser.parseMatches(MatchScriptParser.render(html, script), baseUrl)
            }.onFailure {
                Log.e(TAG, "获取比赛列表失败 domain=$domain", it)
            }
        }

    private fun fetch(url: String): Pair<Int, String> {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
            val body = resp.body?.string() ?: ""
            resp.code to body
        }
    }

    companion object {
        private const val TAG = "MatchRepository"
        const val PRIMARY_DOMAIN = "m.jrskk.com"
        val BACKUP_DOMAINS = listOf("www.jrs04.com", "www.jrs80.com", "www.jrs03.com")
        val ALL_DOMAINS = listOf(PRIMARY_DOMAIN) + BACKUP_DOMAINS

        fun buildBaseUrl(domain: String): String = "https://$domain"

        private fun buildClient(): OkHttpClient {
            return OkHttpClient.Builder()
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
