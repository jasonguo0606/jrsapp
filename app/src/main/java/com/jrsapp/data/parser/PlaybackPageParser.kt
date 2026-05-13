package com.jrsapp.data.parser

import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Base64
import com.jrsapp.data.model.PlaybackPage
import com.jrsapp.data.model.StreamLink
import com.jrsapp.data.model.VideoSource
import com.jrsapp.data.model.VideoSourceType
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.jsoup.Jsoup
import org.json.JSONObject

object PlaybackPageParser {

    private const val TAG = "PlaybackPageParser"
    private const val PAPS_KEY = "ABCDEFGHIJKLMNOPQRSTUVWX"
    private const val ZHJ_AES_KEY = "redq9tmx12kb6s51"
    private const val XXTEA_DELTA = 0x9E3779B9.toInt()

    private val videoUrlRegex = Regex(
        pattern = """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|flv)(?:\?[^"'\\\s<>]*)?""",
        option = RegexOption.IGNORE_CASE
    )

    private val purlHostRegex = Regex("""var\s+purl\s*=\s*["']\/\/([^"'\\]+)["']\s*\+\s*id""", RegexOption.IGNORE_CASE)
    private val kbmmIframeRegex = Regex("""src=['"]/play/kbmm\.php\?id=['"]\s*\+\s*id\s*\+\s*['"]""", RegexOption.IGNORE_CASE)
    private val encodedStrRegex = Regex("""var\s+encodedStr\s*=\s*'([^']+)'""", RegexOption.IGNORE_CASE)
    private val zhjEncryptedRegex = Regex("""var\s+encryptedBase64Str\s*=\s*'([^']+)'""", RegexOption.IGNORE_CASE)
    private val iframeSrcRegex = Regex("""src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val qqCallbackRegex = Regex("""var\s+livegetinfo_callback\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val qqVideoUrlStartRegex = Regex("""(?:https?://)?video\.qq\.com/\?cmd=2""", RegexOption.IGNORE_CASE)
    private val playUrlRegex = Regex("""["']playurl["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun extractPlaybackPage(html: String, pageUrl: String): PlaybackPage {
        val normalizedHtml = html.replace("\\/", "/")
        val doc = Jsoup.parse(normalizedHtml, pageUrl)
        val subLines = mutableListOf<StreamLink>()

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
                            if (element.tagName() == "iframe" && label == "默认入口") {
                                Log.d(TAG, "skip iframe fallback entry candidate=$candidate")
                                return@forEach
                            }
                            if (!shouldKeepSubLineLabel(label)) {
                                Log.d(TAG, "skip filtered subLine label=$label candidate=$candidate")
                                return@forEach
                            }
                            Log.d(
                                TAG,
                                "subLine pageUrl=$pageUrl candidate=$candidate tag=${element.tagName()} text=${element.text().take(50)}"
                            )
                            addSubLine(
                                subLines = subLines,
                                label = label,
                                url = candidate
                            )
                        }
                    }
            }

        extractNestedPageUrls(html, pageUrl)
            .filter { isSubLinePage(it, pageUrl) }
            .forEachIndexed { index, candidate ->
                if (shouldKeepGeneratedSubLine(subLines.isEmpty())) {
                    addSubLine(
                        subLines = subLines,
                        label = "子线路${index + 1}",
                        url = candidate
                    )
                }
            }

        val result = PlaybackPage(
            pageUrl = pageUrl,
            subLines = subLines.toList()
        )
        Log.d(TAG, "extractPlaybackPage subLines=${result.subLines}")
        return result
    }

    private fun addSubLine(
        subLines: MutableList<StreamLink>,
        label: String,
        url: String
    ) {
        if (subLines.none { it.label == label && it.url == url }) {
            subLines += StreamLink(label = label, url = url)
        }
    }

    private fun shouldKeepSubLineLabel(label: String): Boolean {
        val normalized = label.trim()
        if (normalized.isBlank()) return true
        return !normalized.contains("主播") && !normalized.contains("解说")
    }

    private fun shouldKeepGeneratedSubLine(hasNoExplicitSubLines: Boolean): Boolean = hasNoExplicitSubLines

    fun extractVideoSources(html: String, pageUrl: String): List<VideoSource> {
        val normalizedHtml = html.replace("\\/", "/")
        val doc = Jsoup.parse(normalizedHtml, pageUrl)
        val directUrls = linkedSetOf<String>()

        Log.d(TAG, "extractVideoSources pageUrl=$pageUrl htmlLen=${html.length}")

        extractDirectMediaUrl(pageUrl, pageUrl)?.let { resolved ->
            Log.d(TAG, "pageUrl resolved directly pageUrl=$pageUrl resolved=$resolved")
            directUrls.add(resolved)
        }

        extractPlayUrlFromResponse(normalizedHtml, pageUrl)?.let { playUrl ->
            extractDirectMediaUrl(playUrl, pageUrl)?.let { resolved ->
                Log.d(TAG, "response playUrl resolved pageUrl=$pageUrl playUrl=$playUrl resolved=$resolved")
                directUrls.add(resolved)
            }
        }

        if (isQqApiRequest(pageUrl) && directUrls.isEmpty()) {
            Log.d(TAG, "skip qq page dom scan without playUrl pageUrl=$pageUrl")
            return emptyList()
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
        if (isQqApiRequest(pageUrl)) {
            val syntheticOnly = extractSyntheticPlayerUrls(normalizedHtml, pageUrl)
                .filter { it != pageUrl }
                .distinct()
            Log.d(TAG, "extractNestedPageUrls qq syntheticOnly pageUrl=$pageUrl nestedUrls=$syntheticOnly")
            return syntheticOnly
        }
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
                        if (isStaticResource(candidate)) return@forEach
                        if (isIncompletePlayerUrl(candidate)) return@forEach
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
                if (isStaticResource(candidate)) return@forEach
                if (isIncompletePlayerUrl(candidate)) return@forEach
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
            isPapsPage(candidateUrl) -> resolvePapsMediaUrl(candidateUrl)
            isMsssPlayerPage(candidateUrl) -> resolveMsssMediaUrl(candidateUrl, pageUrl)
            is538PlayerPage(candidateUrl) -> resolve538MediaUrl(candidateUrl)
            isMediaUrl(candidateUrl) -> {
                Log.d(TAG, "direct media candidate=$candidateUrl")
                candidateUrl
            }
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

    private fun resolve538MediaUrl(candidateUrl: String): String? {
        val uri = runCatching { Uri.parse(candidateUrl) }.getOrNull() ?: return null
        val playUrl = uri.getQueryParameter("id1")
            ?.takeIf { it.isNotBlank() }
            ?: uri.getQueryParameter("id2")?.takeIf { it.isNotBlank() }
            ?: return null
        val decoded = decodeQueryValue(playUrl)
        Log.d(TAG, "538 player candidate=$candidateUrl decoded=$decoded")
        return decoded.takeIf(::isMediaUrl)
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
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrNull()

        if (path != null) {
            return path.contains(".m3u8") || path.contains(".mp4") || path.contains(".flv")
        }

        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv")
    }

    private fun isStaticResource(url: String): Boolean =
        Regex("""\.(js|css|png|jpg|jpeg|gif|svg|webp)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun isIncompletePlayerUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val id = uri.getQueryParameter("id").orEmpty()
        return when {
            path.endsWith("/kbmm.php") && id.isBlank() -> true
            path.endsWith("/zhj_j.php") && id.isBlank() -> true
            else -> false
        }
    }

    private fun isPapsPage(url: String): Boolean =
        runCatching { Uri.parse(url).path }.getOrNull()?.contains("/player/paps.html", ignoreCase = true) == true

    private fun isMsssPlayerPage(url: String): Boolean =
        runCatching { Uri.parse(url).path }.getOrNull()?.contains("msss.html", ignoreCase = true) == true

    private fun is538PlayerPage(url: String): Boolean =
        runCatching { Uri.parse(url).path }.getOrNull()?.contains("/player/538.html", ignoreCase = true) == true

    private fun isQqApiRequest(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        return (host.contains("video.qq.com") || host.contains("v.qq.com")) &&
            uri.getQueryParameter("cmd") == "2"
    }

    private fun isLikelyPlayerPage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("javascript:")) return false
        if (isMediaUrl(lower)) return true
        if (isStaticResource(lower)) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val path = uri?.path.orEmpty().lowercase()

        if (host.contains("sportsteam") && path.startsWith("/play/")) return true
        if (host.contains("yumixiu768.com") && path.contains("/player/")) return true
        if (host.contains("szsummer.cn") && path.startsWith("/live/")) return true
        if (host.contains("lhrhgb.com") && path.startsWith("/vlive/")) return true
        if ((host.contains("video.qq.com") || host.contains("v.qq.com")) && uri?.getQueryParameter("cmd") == "2") return true

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
            path.contains("/play/i11.html") ||
            path.contains("/play/y.php") ||
            path.contains("/play/j.php") ||
            path.contains("/play/mgxl.php") ||
            path.contains("/play/p/zhj_j.php") ||
            path.contains("/play/a") ||
            path.contains("/player/pap.html") ||
            path.contains("/player/paps.html")
    }

    private fun extractSyntheticPlayerUrls(html: String, pageUrl: String): List<String> {
        val uri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return emptyList()
        val id = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lower = html.lowercase()
        val syntheticPattern = Regex("""src=['"]/play/['"]\s*\+\s*id\d*\s*\+\s*['"]\.html['"]""", RegexOption.IGNORE_CASE)

        if (
            uri.path?.endsWith("/kbmm.php", ignoreCase = true) == true ||
            uri.path?.endsWith("/y.php", ignoreCase = true) == true ||
            uri.path?.endsWith("/mgxl.php", ignoreCase = true) == true
        ) {
            val encoded = encodedStrRegex.find(html)?.groupValues?.getOrNull(1)
            if (!encoded.isNullOrBlank()) {
                val next = "https://cloud.yumixiu768.com/player/paps.html?id=$encoded"
                Log.d(TAG, "synthesized encoded paps url=$next from pageUrl=$pageUrl")
                return listOf(next)
            }
        }

        if (uri.path?.endsWith("/i11.html", ignoreCase = true) == true) {
            val id1 = uri.getQueryParameter("id").orEmpty()
            val id2 = uri.getQueryParameter("id2").orEmpty()
            val iframeSrc = if (id1.isNotBlank()) {
                normalizeUrl("./p/zhj_j.php?id=$id1&id2=$id2", pageUrl)
            } else {
                iframeSrcRegex.find(html)?.groupValues?.getOrNull(1)
                    ?.let { normalizeUrl(it, pageUrl) }
            }
            if (!iframeSrc.isNullOrBlank() && !isStaticResource(iframeSrc)) {
                Log.d(TAG, "synthesized i11 iframe url=$iframeSrc from pageUrl=$pageUrl")
                return listOf(iframeSrc)
            }
        }

        if (uri.path?.endsWith("/play/p/zhj_j.php", ignoreCase = true) == true) {
            val qqUrl = decryptZhjQqUrl(html, pageUrl)
            if (!qqUrl.isNullOrBlank()) {
                Log.d(TAG, "synthesized zhj qq url=$qqUrl from pageUrl=$pageUrl")
                return listOf(qqUrl)
            }
        }

        if (uri.host?.contains("video.qq.com", ignoreCase = true) == true) {
            val playUrl = extractPlayUrlFromResponse(html, pageUrl)
            val next = playUrl?.let(::build538PlayerUrl)
            if (!next.isNullOrBlank()) {
                Log.d(TAG, "synthesized qq 538 url=$next from pageUrl=$pageUrl playUrl=$playUrl")
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

    private fun decryptZhjQqUrl(html: String, pageUrl: String): String? {
        val encrypted = zhjEncryptedRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            val decrypted = decryptZhjPayload(encrypted)
            val callbackName = extractQqCallbackName(html)
            val qqUrl = extractQqApiUrl(decrypted)
                ?.let { sanitizeQqApiUrl(it, callbackName) }
            val qqUrlTail = qqUrl?.takeLast(180).orEmpty()
            Log.d(
                TAG,
                "decryptZhjQqUrl pageUrl=$pageUrl callbackName=$callbackName decryptedPrefix=${decrypted.take(200)} decryptedSuffix=${decrypted.takeLast(200)} qqUrlTail=$qqUrlTail"
            )
            qqUrl?.let { normalizeUrl(it, pageUrl) }
        }.onFailure {
            Log.e(TAG, "decryptZhjQqUrl failed pageUrl=$pageUrl", it)
        }.getOrNull()
    }

    private fun decryptZhjPayload(encrypted: String): String {
        val decodedBytes = Base64.getDecoder().decode(encrypted)
        val keySpec = SecretKeySpec(ZHJ_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val transformations = listOf(
            ZhjCipherConfig("AES/ECB/NoPadding"),
            ZhjCipherConfig("AES/ECB/PKCS5Padding"),
            ZhjCipherConfig("AES/CBC/PKCS5Padding", IvParameterSpec(ByteArray(16)))
        )

        transformations.forEach { config ->
            tryDecryptZhjPayload(
                decodedBytes = decodedBytes,
                keySpec = keySpec,
                config = config
            )?.let { decrypted ->
                Log.d(TAG, "decryptZhjPayload matched transformation=${config.transformation}")
                return decrypted
            }
        }

        error("No valid ZHJ AES transformation matched")
    }

    private fun tryDecryptZhjPayload(
        decodedBytes: ByteArray,
        keySpec: SecretKeySpec,
        config: ZhjCipherConfig
    ): String? =
        runCatching {
            val cipher = Cipher.getInstance(config.transformation)
            if (config.ivSpec != null) {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, config.ivSpec)
            } else {
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
            }
            cipher.doFinal(decodedBytes)
                .toString(Charsets.UTF_8)
                .trim('\u0000')
        }.getOrNull()
            ?.takeIf { it.contains("video.qq.com/?cmd=2", ignoreCase = true) }

    private fun extractPlayUrlFromResponse(html: String, pageUrl: String): String? {
        val rawPlayUrl = playUrlRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val normalized = normalizeUrl(rawPlayUrl, pageUrl)?.replace("\\u0026", "&") ?: return null
        Log.d(TAG, "extractPlayUrlFromResponse pageUrl=$pageUrl playUrl=$normalized")
        return normalized
    }

    private fun build538PlayerUrl(playUrl: String): String? {
        val normalizedPlayUrl = playUrl.takeIf { it.isNotBlank() } ?: return null
        val encoded = java.net.URLEncoder.encode(normalizedPlayUrl, Charsets.UTF_8.name())
        return "https://cloud.yumixiu768.com/player/538.html?id1=$encoded&id2=$encoded"
    }

    private fun extractQqCallbackName(html: String): String =
        qqCallbackRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: "livegetinfo_callback"

    private fun sanitizeQqApiUrl(raw: String, callbackName: String): String {
        val cleaned = raw
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .filterNot { it.isISOControl() }
            .trim()
            .trimEnd(';')
        val withScheme = if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) {
            cleaned
        } else {
            "https://$cleaned"
        }
        val separator = if ('?' in withScheme) '&' else '?'
        val callbackRegex = Regex("""([?&])callback=[^&]*""", RegexOption.IGNORE_CASE)
        return if (callbackRegex.containsMatchIn(withScheme)) {
            withScheme.replace(callbackRegex, "$1callback=$callbackName")
        } else {
            "$withScheme${separator}callback=$callbackName"
        }
    }

    private fun extractQqApiUrl(decrypted: String): String? {
        val start = qqVideoUrlStartRegex.find(decrypted)?.range?.first ?: return null
        val end = findQqApiEnd(decrypted, start)
        return decrypted.substring(start, end)
            .substringBefore(" qqUrl=", missingDelimiterValue = decrypted.substring(start, end))
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun findQqApiEnd(content: String, startIndex: Int): Int {
        val lower = content.lowercase()
        val delimiters = listOf(
            "\r",
            "\n",
            "</script",
            " qqurl=",
            "\tqqurl=",
            " var ",
            " function ",
            " document.",
            " window.",
            "</body",
            "</html"
        )
        return delimiters.mapNotNull { lower.indexOf(it, startIndex).takeIf { index -> index >= 0 } }
            .minOrNull()
            ?: content.length
    }

    private data class ZhjCipherConfig(
        val transformation: String,
        val ivSpec: IvParameterSpec? = null
    )

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
