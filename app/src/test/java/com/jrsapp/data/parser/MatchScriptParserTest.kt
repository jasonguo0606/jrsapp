package com.jrsapp.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchScriptParserTest {
    @Test
    fun parsesNbaMatchFromCurrentSiteScript() {
        val home = """
            <script>
            window.PLAY_HOSTS = {
                line1: atob("aHR0cDovL20uc3BvcnRzdGVhbTUzLmNvbQ=="),
                line2: atob("aHR0cDovL20uancxMTA0LmNvbQ=="),
                line3: atob("aHR0cDovL3BsYXkuc3BvcnRzdGVhbTM1Ni5jb20=")
            };
            </script>
        """.trimIndent()
        val script = """
            document.write('<ul class="item play" data-lid="3925711,2,3925711">');
            document.write('<li class="lab_events"><span class="name">NBA</span></li>');
            document.write('<li class="lab_time">10-09 10:30</li>');
            document.write('<li class="lab_team_home"><strong class="name">湖人</strong></li>');
            document.write('<li class="lab_team_away"><strong class="name">国王</strong></li>');
            document.write('<li class="lab_channel">');
            document.write('<a href="' + getPlayUrl("line1", "823836") + '" data-play="">直播①</a>');
            document.write('<a href="' + getPlayUrl("line2", "823836") + '" data-play="">直播②</a>');
            document.write('<a href="' + getPlayUrl("line3", "823836") + '" data-play=".html">直播③</a>');
            document.write('</li></ul>');
        """.trimIndent()

        val matches = MatchParser.parseMatches(
            MatchScriptParser.render(home, script),
            "https://m.jrskk.com"
        )

        assertEquals(1, matches.size)
        assertEquals("NBA", matches.single().league)
        assertEquals("湖人", matches.single().homeTeam)
        assertEquals("国王", matches.single().awayTeam)
        assertEquals(
            listOf(
                "http://m.sportsteam53.com/play/steam823836.html",
                "http://m.jw1104.com/play/steam823836.html",
                "http://play.sportsteam356.com/play/steam823836.html"
            ),
            matches.single().streamUrls.map { it.url }
        )
    }
}
