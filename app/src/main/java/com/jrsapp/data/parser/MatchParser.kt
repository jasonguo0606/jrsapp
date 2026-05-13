package com.jrsapp.data.parser

import android.util.Log
import com.jrsapp.data.model.Match
import com.jrsapp.data.model.StreamLink
import org.jsoup.Jsoup
import java.net.URI

object MatchParser {

    private const val TAG = "MatchParser"

    fun parseMatches(html: String, baseUrl: String): List<Match> {
        val doc = Jsoup.parse(html)

        // 每场比赛的容器带有 data-lid 属性，且包含 .lab_channel 直播链接
        val containers = doc.select("[data-lid]:has(.lab_channel)")
        Log.d(TAG, "[data-lid]:has(.lab_channel) 数量: ${containers.size}")

        // 如果没找到，降级：通过 lab_channel 反向定位父容器
        val items = if (containers.isNotEmpty()) {
            containers
        } else {
            doc.select(".lab_channel").map { el ->
                el.parents().firstOrNull { it.hasAttr("data-lid") } ?: el.parent() ?: el
            }.distinctBy { it.hashCode() }
                .let { org.jsoup.select.Elements(it) }
                .also { Log.d(TAG, "降级策略找到: ${it.size}") }
        }

        val results = items.mapIndexedNotNull { i, el -> parseItem(el, i, baseUrl) }
        Log.d(TAG, "解析出 ${results.size} 场比赛")
        results.take(5).forEach { Log.d(TAG, "  联赛=${it.league}  ${it.homeTeam} vs ${it.awayTeam}") }
        return results
    }

    private fun parseItem(el: org.jsoup.nodes.Element, index: Int, baseUrl: String): Match? {
        return try {
            Log.d(TAG, "parseItem[$index] data-lid=${el.attr("data-lid")} html=${el.outerHtml().take(500)}")
            val streamLinks = el.select(".lab_channel a")
                .mapIndexed { i, a ->
                    val rawUrl = a.attr("data-play").takeIf { it.isNotBlank() && it != "#" && !it.startsWith("javascript:", true) }
                        ?: a.attr("href")
                    val normalized = normalizeUrl(rawUrl, baseUrl)
                    Log.d(TAG, "parseItem[$index] line${i + 1} href=${a.attr("href")} data-play=${a.attr("data-play")} normalized=$normalized")
                    normalized.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                        ?.let { StreamLink(label = "线路${i + 1}", url = it) }
                }
                .filterNotNull()
            if (streamLinks.isEmpty()) return null

            val league   = el.selectFirst(".lab_events span.name, .lab_events .name")?.text()?.trim() ?: ""
            val time     = el.selectFirst(".lab_time")?.text()?.trim() ?: ""
            val homeTeam = el.selectFirst(".lab_team_home strong.name")?.text()?.trim() ?: ""
            val awayTeam = el.selectFirst(".lab_team_away strong.name")?.text()?.trim() ?: ""
            val homeLogo = el.selectFirst(".lab_team_home .avatar img, .lab_team_home img")?.attr("abs:src") ?: ""
            val awayLogo = el.selectFirst(".lab_team_away .avatar img, .lab_team_away img")?.attr("abs:src") ?: ""

            // 比分：lab_vs 里的数字
            val scoreRaw = el.selectFirst(".lab_vs")?.text()
                ?.replace(Regex("[^\\d\\-:]"), "")?.trim() ?: ""
            val score = if (scoreRaw.any { it.isDigit() }) scoreRaw else ""

            if (homeTeam.isBlank() && awayTeam.isBlank()) return null

            val lid = el.attr("data-lid").ifBlank { "$index" }

            Match(
                id = lid,
                league = league,
                time = time,
                homeTeam = homeTeam,
                homeLogoUrl = homeLogo,
                awayTeam = awayTeam,
                awayLogoUrl = awayLogo,
                score = score,
                isLive = score.any { it.isDigit() },
                streamUrls = streamLinks
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseItem[$index] 失败", e)
            null
        }
    }

    fun isNba(league: String) = league.contains("NBA", ignoreCase = true)
    fun isCba(league: String) = league.contains("CBA", ignoreCase = true)
    fun isBasketball(league: String) =
        isNba(league) || isCba(league) || league.contains("篮球", ignoreCase = true)

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> URI(baseUrl).resolve(trimmed).toString()
            trimmed.startsWith("javascript:", true) -> ""
            else -> trimmed
        }
    }
}
