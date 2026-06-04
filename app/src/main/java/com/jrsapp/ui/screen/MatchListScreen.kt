package com.jrsapp.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jrsapp.data.model.Match
import kotlinx.coroutines.delay

// ── Scoreboard palette ──────────────────────────────────────────────────────
private val BgBase      = Color(0xFF030303)
private val BgCard      = Color(0xFF0C0C0C)
private val BgCardLive  = Color(0xFF0E0C08)
private val BdrCard     = Color(0xFF1A1A1A)
private val BdrLive     = Color(0xFF2A1A00)
private val Divider1    = Color(0xFF111111)
private val Amber       = Color(0xFFF5A623)
private val LiveGreen   = Color(0xFF39FF14)
private val TextTeam    = Color(0xFFAAAAAA)
private val TextLeague  = Color(0xFF666666)
private val TextTime    = Color(0xFF5A5A5A)
private val TextStream  = Color(0xFF4FC3F7)
private val TabOff      = Color(0xFF666666)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchListScreen(
    onMatchClick: (Match) -> Unit,
    viewModel: MatchListViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter  by viewModel.filter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "JRS 篮球直播",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp,
                            color = Amber
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BgBase,
                        titleContentColor = Amber
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.loadMatches() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = TabOff)
                        }
                    }
                )
                HorizontalDivider(thickness = 2.dp, color = Divider1)
            }
        },
        containerColor = BgBase
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScoreboardTabs(selected = filter, onSelect = { viewModel.setFilter(it) })

            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                    is MatchListUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Amber
                    )

                    is MatchListUiState.Error -> ErrorView(
                        message = state.message,
                        onRetry = { viewModel.loadMatches() },
                        currentDomain = state.currentDomain,
                        backupDomains = state.backupDomains,
                        onSwitchDomain = viewModel::switchDomain,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    is MatchListUiState.Success -> {
                        if (state.matches.isEmpty()) {
                            Text(
                                text = "暂无 ${filter.label} 比赛",
                                color = TextLeague,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            MatchList(matches = state.matches, onMatchClick = onMatchClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreboardTabs(selected: LeagueFilter, onSelect: (LeagueFilter) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgBase)
        ) {
            LeagueFilter.entries.forEach { f ->
                val isSelected = f == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(f) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = f.label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (isSelected) Amber else TabOff,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (isSelected) Amber else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Divider1)
    }
}

@Composable
private fun MatchList(matches: List<Match>, onMatchClick: (Match) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(matches, key = { it.id }) { match ->
            ScoreboardCard(match = match, onClick = { onMatchClick(match) })
        }
    }
}

@Composable
private fun ScoreboardCard(match: Match, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, if (match.isLive) BdrLive else BdrCard)
            .background(if (match.isLive) BgCardLive else BgCard)
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.league,
                    color = TextLeague,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
                if (match.isLive) LiveIndicator()
                else Text(
                    text = match.time,
                    color = TextTime,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScoreboardLogo(url = match.homeLogoUrl)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = match.homeTeam,
                        color = TextTeam,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (match.isLive && match.score.isNotBlank()) {
                        Text(
                            text = match.score,
                            color = Amber,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    } else {
                        Text(
                            text = "VS",
                            color = TextTime,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = match.awayTeam,
                        color = TextTeam,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    ScoreboardLogo(url = match.awayLogoUrl)
                }
            }

            if (match.streamUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = Divider1)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "[ ${match.streamUrls.size} 条播放线路 ] ›",
                    color = TextStream,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun LiveIndicator() {
    var dotVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            dotVisible = !dotVisible
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(
                    color = if (dotVisible) LiveGreen else Color.Transparent,
                    shape = CircleShape
                )
        )
        Text(
            text = "LIVE",
            color = LiveGreen,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun ScoreboardLogo(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier
            .size(32.dp)
            .border(1.dp, Color(0xFF1F1F1F))
            .background(Color(0xFF111111))
    )
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    currentDomain: String,
    backupDomains: List<String>,
    onSwitchDomain: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ERROR: $message",
            color = TabOff,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "HOST: $currentDomain",
            color = TextStream,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Amber)
        ) {
            Text("RETRY", fontFamily = FontFamily.Monospace, color = Color.Black, letterSpacing = 2.sp)
        }
        if (backupDomains.isNotEmpty()) {
            Text(
                text = "── 备用节点 ──",
                color = TabOff,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            backupDomains.forEach { domain ->
                OutlinedButton(
                    onClick = { onSwitchDomain(domain) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Divider1)
                ) {
                    Text(domain, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextStream)
                }
            }
        }
    }
}
