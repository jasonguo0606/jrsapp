package com.jrsapp.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jrsapp.data.model.Match

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchListScreen(
    onMatchClick: (Match) -> Unit,
    viewModel: MatchListViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JRS 篮球直播", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadMatches() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
                    }
                }
            )
        },
        containerColor = Color(0xFF0F0F23)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 联赛筛选 Tab
            LeagueFilterTabs(
                selected = filter,
                onSelect = { viewModel.setFilter(it) }
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                    is MatchListUiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xFFE88C23)
                        )
                    }

                    is MatchListUiState.Error -> {
                        ErrorView(
                            message = state.message,
                            onRetry = { viewModel.loadMatches() },
                            currentDomain = state.currentDomain,
                            backupDomains = state.backupDomains,
                            onSwitchDomain = viewModel::switchDomain,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is MatchListUiState.Success -> {
                        if (state.matches.isEmpty()) {
                            Text(
                                text = "暂无 ${filter.label} 比赛",
                                color = Color.Gray,
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
private fun LeagueFilterTabs(
    selected: LeagueFilter,
    onSelect: (LeagueFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A2E))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LeagueFilter.entries.forEach { f ->
            val isSelected = f == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Color(0xFFE88C23) else Color(0xFF2A2A4A))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = f.label,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun MatchList(matches: List<Match>, onMatchClick: (Match) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(matches, key = { it.id }) { match ->
            MatchCard(match = match, onClick = { onMatchClick(match) })
        }
    }
}

@Composable
private fun MatchCard(match: Match, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E3A))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.league,
                    color = Color(0xFFE88C23),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (match.isLive) {
                    LiveBadge()
                } else {
                    Text(text = match.time, color = Color.Gray, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TeamInfo(name = match.homeTeam, logoUrl = match.homeLogoUrl, modifier = Modifier.weight(1f))
                ScoreBox(score = match.score, isLive = match.isLive)
                TeamInfo(name = match.awayTeam, logoUrl = match.awayLogoUrl, modifier = Modifier.weight(1f), alignEnd = true)
            }

            if (match.streamUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${match.streamUrls.size} 条直播线路  >",
                    color = Color(0xFF4FC3F7),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun TeamInfo(
    name: String,
    logoUrl: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        if (!alignEnd) {
            TeamLogo(logoUrl)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (alignEnd) {
            Spacer(modifier = Modifier.width(6.dp))
            TeamLogo(logoUrl)
        }
    }
}

@Composable
private fun TeamLogo(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(4.dp))
    )
}

@Composable
private fun ScoreBox(score: String, isLive: Boolean) {
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isLive) Color(0xFF2A2A4A) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (score.isBlank()) "vs" else score,
            color = if (isLive) Color.White else Color.Gray,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun LiveBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFE53935))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = "直播中", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
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
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "加载失败: $message", color = Color.Gray, fontSize = 14.sp)
        Text(
            text = "当前域名: $currentDomain",
            color = Color(0xFF4FC3F7),
            fontSize = 12.sp
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE88C23))
        ) {
            Text("重试")
        }
        if (backupDomains.isNotEmpty()) {
            Text(
                text = "主域名不可用时，可切换备用域名",
                color = Color.Gray,
                fontSize = 12.sp
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                backupDomains.forEach { domain ->
                    OutlinedButton(
                        onClick = { onSwitchDomain(domain) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFF4FC3F7))
                    ) {
                        Text(domain, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
