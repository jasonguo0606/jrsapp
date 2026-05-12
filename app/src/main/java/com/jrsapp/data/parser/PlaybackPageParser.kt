package com.jrsapp.data.parser

import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Base64
import com.jrsapp.data.model.PlaybackPage
import com.jrsapp.data.model.StreamLink
import com.jrsapp.data.model.VideoSource
import com.jrsapp.data.model.VideoSourceType
import org.jsoup.Jsoup
import org.json.JSONObject

object PlaybackPageParser {

    private const val TAG = "PlaybackPageParser"
    private const val PAPS_KEY = "ABCDEFGHIJKLMNOPQRSTUVWX"
    private const val XXTEA_DELTA = 0x9E3779B9.toInt()

    private val videoUrlRegex = Regex(
        pattern = """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|flv)(?:\?[^"'\\\s<>]*)?""",
        option = RegexOption.IGNORE_CASE
    )

    private val purlHostRegex = Regex("""var\s+purl\s*=\s*["']\/\/([^"'\\]+)["']\s*\+\s*id""", RegexOption.IGNORE_CASE)
    private val kbmmIframeRegex = Regex("""src=['"]/play/kbmm\.php\?id=['"]\s*\+\s*id\s*\+\s*['"]""", RegexOption.IGNORE_CASE)
    private val encodedStrRegex = Regex("""var\s+encodedStr\s*=\s*'([^']+)'""", RegexOption.IGNORE_CASE)

    fun extractPlaybackPage(html: String, pageUrl: String): PlaybackPage {
        val normalizedHtml = html.replace("\\/", "/")
        val doc = Jsoup.parse(normalizedHtml, pageUrl)
        val subLines = linkedMapOf<String, StreamLink>()

        Log.d(TAG, "extractPlaybackPage pageUrl=$pageUrl htmlLen=${html.length}")

        doc.select("a[href], a[data-play], a[data-src], a[data-url], iframe[src], frame[src]")
            .forEach { element ->
                listOf(
                    element.absUrl("href"),
                    element.absUrl("src"),
                    element.attr("data-play"),
                    element.attr("data-src"),
                    element.attr("data-url")
                )
                    .filter { it.isNotBlank() }
                    .mapNotNull { normalizeUrl(it, pageUrl) }
                    .forEach { candidate ->
                        if (isSubLinePage(candidate, pageUrl)) {
                            val label = element.text().trim()
                                .ifBlank { if (element.tagName() == "iframe") "默认入口" else "子线路${subLines.size + 1}" }
                            Log.d(
                                TAG,
                                "subLine pageUrl=$pageUrl candidate=$candidate tag=${element.tagName()} text=${element.text().take(50)}"
                            )
                            val existing = subLines[candidate]
                            if (existing == null || existing.label.startsWith("默认") || existing.label.startsWith("子线路")) {
                                subLines[candidate] = StreamLink(label = label, url = candidate)
                            }
                        }
                    }
            }

        extractNestedPageUrls(html, pageUrl)
            .filter { isSubLinePage(it, pageUrl) }
            .forEachIndexed { index, candidate ->
                val existing = subLines[candidate]
                if (existing == null) {
                    subLines[candidate] = StreamLink(label = "子线路${index + 1}", url = candidate)
                }
            }

        val result = PlaybackPage(
            pageUrl = pageUrl,
            subLines = subLines.values.toList()
        )
        Log.d(TAG, "extractPlaybackPage subLines=${result.subLines}")
        return result
    }

    fun extractVideoSources(html: String, pageUrl: String): List<VideoSource> {
        val normalizedHtml = html.replace("\\/", "/")
        val doc = Jsoup.parse(normalizedHtml, pageUrl)
        val directUrls = linkedSetOf<String>()

        Log.d(TAG, "extractVideoSources pageUrl=$pageUrl htmlLen=${html.length}")

        extractDirectMediaUrl(pageUrl, pageUrl)?.let { resolved ->
            Log.d(TAG, "pageUrl resolved directly pageUrl=$pageUrl resolved=$resolved")
            directUrls.add(resolved)
        }

        doc.select("video[src], video source[src], source[src], iframe[src], frame[src], embed[src], a[href], a[data-play], a[data-src], a[data-url]")
            .forEach { element ->
                listOf(
                    element.absUrl("src"),
                    element.absUrl("href"),
                    element.attr("data-play"),
                    element.attr("data-src"),
                    element.attr("data-url")
                )
                    .filter { it.isNotBlank() }
                    .mapNotNull { normalizeUrl(it, pageUrl) }
                    .forEach { candidate ->
                        Log.d(TAG, "candidate pageUrl=$pageUrl candidate=$candidate tag=${element.tagName()} text=${element.text().take(50)}")
                        extractDirectMediaUrl(candidate, pageUrl)?.let(directUrls::add)
                    }
            }

        purlHostRegex.find(normalizedHtml)?.groupValues?.getOrNull(1)?.let { host ->
            extractIdParam(pageUrl)?.let { idValue ->
                val url = "https://$host${decodeQueryValue(idValue)}"
                if (isMediaUrl(url)) {
                    directUrls.add(url)
                }
            }
        }

        videoUrlRegex.findAll(normalizedHtml).forEach { match ->
            val normalized = normalizeUrl(match.value, pageUrl)
            Log.d(TAG, "regex media match=${match.value} normalized=$normalized")
            normalized?.let(directUrls::add)
        }

        Log.d(TAG, "extractVideoSources directUrls=${directUrls.toList()}")
        return directUrls.mapIndexed { index, url -> buildVideoSource(url = url, index = index, referer = pageUrl) }
    }

    fun extractNestedPageUrls(html: String, pageUrl: String): List<String> {
        val normalizedHtml = html.replace("\\/", "/")
        val doc = Jsoup.parse(normalizedHtml, pageUrl)
        val nestedUrls = linkedSetOf<String>()

        Log.d(TAG, "extractNestedPageUrls pageUrl=$pageUrl htmlLen=${html.length}")

        doc.select("iframe[src], frame[src], embed[src], a[href], a[data-play], a[data-src], a[data-url]")
            .forEach { element ->
                listOf(
                    element.absUrl("src"),
                    element.absUrl("href"),
                    element.attr("data-play"),
                    element.attr("data-src"),
                    element.attr("data-url")
                    )
                    .filter { it.isNotBlank() }
                    .mapNotNull { normalizeUrl(it, pageUrl) }
                    .forEach { candidate ->
                        if (candidate.endsWith("kbmm.php?id=")) return@forEach
                        if (isLikelyPlayerPage(candidate) && candidate != pageUrl) {
                            Log.d(TAG, "nested candidate=$candidate tag=${element.tagName()} text=${element.text().take(50)}")
                            nestedUrls.add(candidate)
                        }
                    }
            }

        Regex("""(?:(?:src|url|playUrl)\s*[:=]\s*["'])([^"']+)""", RegexOption.IGNORE_CASE)
            .findAll(normalizedHtml)
            .mapNotNull { normalizeUrl(it.groupValues[1], pageUrl) }
            .forEach { candidate ->
                if (candidate.endsWith("kbmm.php?id=")) return@forEach
                if (isLikelyPlayerPage(candidate) && candidate != pageUrl) {
                    Log.d(TAG, "nested regex candidate=$candidate")
                    nestedUrls.add(candidate)
                }
            }

        extractSyntheticPlayerUrls(normalizedHtml, pageUrl).forEach { syntheticUrl ->
            if (syntheticUrl != pageUrl) {
                Log.d(TAG, "synthetic nested pageUrl=$pageUrl candidate=$syntheticUrl")
                nestedUrls.add(syntheticUrl)
            }
        }

        Log.d(TAG, "extractNestedPageUrls nestedUrls=${nestedUrls.toList()}")
        return nestedUrls.toList()
    }

    private fun normalizeUrl(raw: String, pageUrl: String): String? {
        val cleaned = raw
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .removePrefix("'")
            .removeSuffix("'")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (cleaned.isBlank() || cleaned.startsWith("javascript:", ignoreCase = true)) {
            return null
        }

        if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)) {
            return cleaned
        }

        if (cleaned.startsWith("//")) {
            val scheme = Uri.parse(pageUrl).scheme ?: "https"
            return "$scheme:$cleaned"
        }

        return runCatching {
            java.net.URI(pageUrl).resolve(cleaned).toString()
        }.getOrNull()
    }

    private fun extractDirectMediaUrl(candidateUrl: String, pageUrl: String): String? =
        when {
            isMediaUrl(candidateUrl) -> {
                Log.d(TAG, "direct media candidate=$candidateUrl")
                candidateUrl
            }
            isPapsPage(candidateUrl) -> resolvePapsMediaUrl(candidateUrl)
            isMsssPlayerPage(candidateUrl) -> resolveMsssMediaUrl(candidateUrl, pageUrl)
            else -> {
                val path = runCatching { Uri.parse(candidateUrl).path }.getOrNull()
                Log.d(TAG, "skip non-player candidate=$candidateUrl path=$path")
                null
            }
        }

    private fun resolvePapsMediaUrl(candidateUrl: String): String? {
        val encrypted = extractRawQueryValue(candidateUrl, "id")?.takeIf { it.isNotBlank() } ?: return null
        val decrypted = decryptPapsUrl(encrypted)
        Log.d(TAG, "paps candidate=$candidateUrl decrypted=$decrypted")
        return decrypted?.takeIf(::isMediaUrl)
    }

    private fun resolveMsssMediaUrl(candidateUrl: String, pageUrl: String): String? {
        val uri = runCatching { Uri.parse(candidateUrl) }.getOrNull() ?: return null
        val idValue = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
        val decodedId = decodeQueryValue(idValue)
        Log.d(TAG, "playerPage candidate=$candidateUrl id=$idValue decoded=$decodedId")

        return when {
            decodedId.startsWith("/live/", ignoreCase = true) -> {
                val resolved = "https://hdl6.szsummer.cn$decodedId"
                Log.d(TAG, "decoded live path resolved=$resolved")
                resolved
            }
            decodedId.startsWith("http://", ignoreCase = true) || decodedId.startsWith("https://", ignoreCase = true) -> {
                Log.d(TAG, "decoded absolute url=$decodedId")
                decodedId
            }
            isMediaUrl(decodedId) -> {
                val resolved = resolveRelativeUrl(decodedId, candidateUrl)
                Log.d(TAG, "decoded media url resolved=$resolved")
                resolved
            }
            else -> {
                val host = extractMediaBaseHost(candidateUrl, pageUrl)
                val resolved = host?.let { "https://$it$decodedId" }
                Log.d(TAG, "decoded fallback host=$host resolved=$resolved")
                resolved
            }
        }
    }

    private fun extractMediaBaseHost(candidateUrl: String, pageUrl: String): String? {
        val htmlHost = runCatching { Uri.parse(candidateUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        if (htmlHost != null && htmlHost.contains("cloud", ignoreCase = true)) {
            return "hdl6.szsummer.cn"
        }
        return runCatching { Uri.parse(pageUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractIdParam(url: String): String? =
        runCatching { Uri.parse(url).getQueryParameter("id") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun decodeQueryValue(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrElse { value }

    private fun extractRawQueryValue(url: String, name: String): String? {
        val marker = "$name="
        val start = url.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = url.indexOf("######", from).takeIf { it >= 0 }
            ?: url.indexOf('&', from).takeIf { it >= 0 }
            ?: url.length
        return url.substring(from, end)
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv")
    }

    private fun isPapsPage(url: String): Boolean =
        runCatching { Uri.parse(url).path }.getOrNull()?.contains("/player/paps.html", ignoreCase = true) == true

    private fun isMsssPlayerPage(url: String): Boolean =
        runCatching { Uri.parse(url).path }.getOrNull()?.contains("msss.html", ignoreCase = true) == true

    private fun isLikelyPlayerPage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("javascript:")) return false
        if (isMediaUrl(lower)) return true
        if (Regex("""\.(js|css|png|jpg|jpeg|gif|svg|webp)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val path = uri?.path.orEmpty().lowercase()

        if (host.contains("sportsteam") && path.startsWith("/play/")) return true
        if (host.contains("yumixiu768.com") && path.contains("/player/")) return true
        if (host.contains("szsummer.cn") && path.startsWith("/live/")) return true
        if (host.contains("lhrhgb.com") && path.startsWith("/vlive/")) return true

        return false
    }

    private fun isSubLinePage(candidateUrl: String, currentPageUrl: String): Boolean {
        if (candidateUrl == currentPageUrl) return false
        if (!isLikelyPlayerPage(candidateUrl)) return false

        val currentUri = runCatching { Uri.parse(currentPageUrl) }.getOrNull()
        val candidateUri = runCatching { Uri.parse(candidateUrl) }.getOrNull() ?: return false
        val path = candidateUri.path.orEmpty().lowercase()
        val currentPath = currentUri?.path.orEmpty().lowercase()

        if (candidateUri.fragment != null && candidateUri.buildUpon().fragment(null).build().toString() == currentPageUrl) {
            return false
        }

        if (candidateUri.host.equals(currentUri?.host, ignoreCase = true) && path == currentPath) {
            return false
        }

        return path.contains("/play/sm.html") ||
            path.contains("/play/kbs.html") ||
            path.contains("/play/y.php") ||
            path.contains("/play/j.php") ||
            path.contains("/play/mgxl.php") ||
            path.contains("/play/a") ||
            path.contains("/player/pap.html") ||
            path.contains("/player/paps.html")
    }

    private fun extractSyntheticPlayerUrls(html: String, pageUrl: String): List<String> {
        val uri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return emptyList()
        val id = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lower = html.lowercase()
        val syntheticPattern = Regex("""src=['"]/play/['"]\s*\+\s*id\d*\s*\+\s*['"]\.html['"]""", RegexOption.IGNORE_CASE)

        if (uri.path?.endsWith("/kbmm.php", ignoreCase = true) == true) {
            val encoded = encodedStrRegex.find(html)?.groupValues?.getOrNull(1)
            if (!encoded.isNullOrBlank()) {
                val next = "https://cloud.yumixiu768.com/player/paps.html?id=$encoded"
                Log.d(TAG, "synthesized kbmm paps url=$next from pageUrl=$pageUrl")
                return listOf(next)
            }
        }

        val shouldSynthesize = lower.contains("src='/play/'+id1+'.html'") ||
            lower.contains("src=\"/play/\"+id1+\".html\"") ||
            lower.contains("src='/play/' + id1 + '.html'") ||
            lower.contains("src=\"/play/\" + id1 + \".html\"") ||
            syntheticPattern.containsMatchIn(html) ||
            kbmmIframeRegex.containsMatchIn(html) ||
            (uri.path?.endsWith("/sm.html", ignoreCase = true) == true)

        if (!shouldSynthesize) return emptyList()

        val host = uri.host ?: return emptyList()
        val scheme = uri.scheme ?: "http"
        val next = when {
            uri.path?.endsWith("/kbs.html", ignoreCase = true) == true ->
                "$scheme://$host/play/kbmm.php?id=$id"
            else ->
                "$scheme://$host/play/$id.html"
        }
        Log.d(TAG, "synthesized next player url=$next from pageUrl=$pageUrl")
        return listOf(next)
    }

    private fun resolveRelativeUrl(raw: String, baseUrl: String): String =
        when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> runCatching { java.net.URI(baseUrl).resolve(raw).toString() }.getOrDefault(raw)
            else -> raw
        }

    private fun decryptPapsUrl(encrypted: String): String? {
        if (encrypted.isBlank()) return null
        return runCatching {
            val decoded = Base64.getDecoder().decode(encrypted)
            val decrypted = xxteaDecrypt(decoded, PAPS_KEY.toByteArray(Charsets.UTF_8)) ?: return null
            val json = decrypted.toString(Charsets.UTF_8)
            Log.d(TAG, "decryptPapsUrl payload=$json")
            JSONObject(json).optString("url").takeIf { it.isNotBlank() }
        }.onFailure {
            Log.e(TAG, "decryptPapsUrl failed", it)
        }.getOrNull()
    }

    private fun xxteaDecrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.isEmpty()) return data
        val v = toIntArray(data, includeLength = false)
        val k = fixKey(toIntArray(key, includeLength = false))
        val decrypted = xxteaDecryptIntArray(v, k)
        return toByteArray(decrypted, includeLength = true)
    }

    private fun xxteaDecryptIntArray(v: IntArray, k: IntArray): IntArray {
        val n = v.size
        if (n < 2) return v
        var y = v[0]
        var z = v[n - 1]
        var sum = toUInt32(((6 + 52 / n).toLong() * XXTEA_DELTA.toLong()))
        while (sum != 0) {
            val e = (sum ushr 2) and 3
            for (p in n - 1 downTo 1) {
                z = v[p - 1]
                y = toUInt32(v[p].toLong() - mx(sum, y, z, p, e, k).toLong())
                v[p] = y
            }
            z = v[n - 1]
            y = toUInt32(v[0].toLong() - mx(sum, y, z, 0, e, k).toLong())
            v[0] = y
            sum = toUInt32(sum.toLong() - XXTEA_DELTA.toLong())
        }
        return v
    }

    private fun mx(sum: Int, y: Int, z: Int, p: Int, e: Int, k: IntArray): Int {
        val left = toUInt32(((z ushr 5) xor (y shl 2)).toLong() + (((y ushr 3) xor (z shl 4)).toLong()))
        val right = toUInt32((sum xor y).toLong() + ((k[(p and 3) xor e] xor z).toLong()))
        return toUInt32((left xor right).toLong())
    }

    private fun toIntArray(data: ByteArray, includeLength: Boolean): IntArray {
        val n = if (data.size and 3 == 0) data.size ushr 2 else (data.size ushr 2) + 1
        val result = if (includeLength) IntArray(n + 1) else IntArray(n)
        if (includeLength) {
            result[n] = data.size
        }
        for (i in data.indices) {
            result[i ushr 2] = result[i ushr 2] or ((data[i].toInt() and 0xff) shl ((i and 3) shl 3))
        }
        return result
    }

    private fun toByteArray(data: IntArray, includeLength: Boolean): ByteArray? {
        var n = data.size shl 2
        if (includeLength) {
            val m = data.last()
            n -= 4
            if (m > n || m < n - 3) {
                Log.d(TAG, "toByteArray invalid length m=$m n=$n words=${data.size}")
                return null
            }
            n = m
        }
        val output = ByteArrayOutputStream(data.size * 4)
        data.forEach { value ->
            output.write(value and 0xff)
            output.write(value ushr 8 and 0xff)
            output.write(value ushr 16 and 0xff)
            output.write(value ushr 24 and 0xff)
        }
        return output.toByteArray().copyOf(n)
    }

    private fun fixKey(key: IntArray): IntArray =
        if (key.size >= 4) key else IntArray(4).also { key.copyInto(it) }

    private fun toUInt32(value: Long): Int = (value and 0xffffffffL).toInt()

    private fun buildVideoSource(url: String, index: Int, referer: String): VideoSource =
        VideoSource(
            url = url,
            type = guessVideoType(url),
            label = "源${index + 1}",
            referer = referer
        )

    private fun guessVideoType(url: String): VideoSourceType {
        val lower = url.lowercase()
        return when {
            ".m3u8" in lower -> VideoSourceType.HLS
            ".flv" in lower -> VideoSourceType.FLV
            ".mp4" in lower -> VideoSourceType.MP4
            else -> VideoSourceType.UNKNOWN
        }
    }
}
