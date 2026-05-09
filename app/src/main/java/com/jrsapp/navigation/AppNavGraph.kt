package com.jrsapp.navigation

import androidx.compose.runtime.*
import com.jrsapp.data.model.Match
import com.jrsapp.ui.screen.MatchListScreen
import com.jrsapp.ui.screen.PlayerScreen

@Composable
fun AppNavGraph() {
    var selectedMatch by remember { mutableStateOf<Match?>(null) }

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
