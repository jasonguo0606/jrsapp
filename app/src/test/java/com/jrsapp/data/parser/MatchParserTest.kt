package com.jrsapp.data.parser

import org.junit.Assert.assertTrue
import org.junit.Test

class MatchParserTest {

    @Test
    fun summerLeagueIsClassifiedAsNba() {
        assertTrue(MatchParser.isNba("夏季联赛"))
    }

}
