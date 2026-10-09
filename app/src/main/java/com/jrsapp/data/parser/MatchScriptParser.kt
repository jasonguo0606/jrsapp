package com.jrsapp.data.parser

import java.util.Base64

object MatchScriptParser {
    private val hostPattern = Regex("""(line\d+)\s*:\s*atob\("([A-Za-z0-9+/=]+)"\)""")
    private val writeCallPattern = Regex("""document\.write\(""")
    private val writePattern = Regex("""document\.write\('(.+?)'\);""")
    private val playUrlPattern = Regex("""'\s*\+\s*getPlayUrl\("([^"]+)",\s*"(\d+)"\)\s*\+\s*'""")

    fun render(homeHtml: String, script: String): String {
        val hosts = hostPattern.findAll(homeHtml).associate { match ->
            match.groupValues[1] to String(Base64.getDecoder().decode(match.groupValues[2]), Charsets.UTF_8)
        }
        val writes = writePattern.findAll(script).toList()
        require(writes.isNotEmpty() || script.isBlank()) { "赛事脚本格式已变化" }
        require(writes.size == writeCallPattern.findAll(script).count()) {
            "赛事脚本格式已变化"
        }

        // shortcut: only the site's current document.write/getPlayUrl format is supported; update here if it changes.
        return writes.joinToString(separator = "") { write ->
            val html = playUrlPattern.replace(write.groupValues[1]) { call ->
                val host = hosts[call.groupValues[1]] ?: error("缺少播放线路 ${call.groupValues[1]}")
                "$host/play/steam${call.groupValues[2]}.html"
            }
            require("getPlayUrl(" !in html) { "赛事脚本线路格式已变化" }
            html.replace("\\'", "'").replace("\\\\", "\\")
        }
    }
}
