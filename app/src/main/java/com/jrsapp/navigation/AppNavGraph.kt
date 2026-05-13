package com.jrsapp.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.jrsapp.data.model.Match
import com.jrsapp.data.model.StreamLink
import com.jrsapp.ui.screen.MatchListScreen
import com.jrsapp.ui.screen.PlayerScreen

@Composable
fun AppNavGraph() {
    var selectedMatch by rememberSaveable(stateSaver = matchSaver()) { mutableStateOf<Match?>(null) }

    if (selectedMatch == null) {
        MatchListScreen(
            onMatchClick = { match -> selectedMatch = match }
        )
    } else {
        PlayerScreen(
            match = selectedMatch!!,
            onBack = { selectedMatch = null }
        )
    }
}

private fun matchSaver(): Saver<Match?, Any> =
    Saver(
        save = { match ->
            match?.let {
                listOf(
                    it.id,
                    it.league,
                    it.time,
                    it.homeTeam,
                    it.homeLogoUrl,
                    it.awayTeam,
                    it.awayLogoUrl,
                    it.score,
                    it.isLive,
                    it.streamUrls.flatMap { link -> listOf(link.label, link.url) }
                )
            }
        },
        restore = { saved ->
            val values = saved as? List<*> ?: return@Saver null
            val streamValues = values.getOrNull(9) as? List<*> ?: emptyList<Any?>()
            Match(
                id = values.getOrNull(0) as? String ?: return@Saver null,
                league = values.getOrNull(1) as? String ?: "",
                time = values.getOrNull(2) as? String ?: "",
                homeTeam = values.getOrNull(3) as? String ?: "",
                homeLogoUrl = values.getOrNull(4) as? String ?: "",
                awayTeam = values.getOrNull(5) as? String ?: "",
                awayLogoUrl = values.getOrNull(6) as? String ?: "",
                score = values.getOrNull(7) as? String ?: "",
                isLive = values.getOrNull(8) as? Boolean ?: false,
                streamUrls = streamValues.chunked(2).mapNotNull { chunk ->
                    val label = chunk.getOrNull(0) as? String ?: return@mapNotNull null
                    val url = chunk.getOrNull(1) as? String ?: return@mapNotNull null
                    StreamLink(label = label, url = url)
                }
            )
        }
    )
