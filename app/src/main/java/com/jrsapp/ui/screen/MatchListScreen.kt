package com.jrsapp.ui.screen

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jrsapp.data.model.Match
import kotlinx.coroutines.delay

private val BgBase      = Color(0xFF030303)
private val Divider1    = Color(0xFF242424)
private val Amber       = Color(0xFFF5A623)
private val LiveGreen   = Color(0xFF39FF14)
private val TextTeam    = Color(0xFFF2F2F2)
private val TextLeague  = Color(0xFF969696)
private val TextTime    = Color(0xFF929292)
private val TextStream  = Color(0xFF4FC3F7)
private val TabOff      = Color(0xFF999999)
private val MatchDatePattern = Regex("""\d{1,2}-\d{1,2}""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchListScreen(
    onMatchClick: (Match) -> Unit,
    viewModel: MatchListViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter  by viewModel.filter.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshIfStale()
                delay(60_000)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "JRS 篮球直播",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            color = Amber
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BgBase,
                        titleContentColor = Amber
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.loadMatches() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = TextLeague)
                        }
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = Divider1)
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
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = if (state.refreshing) "正在更新比赛…" else
                                    "更新于 ${DateFormat.format("HH:mm", state.updatedAtMillis)}",
                                color = TextTime,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                            if (state.refreshError != null) {
                                Text(
                                    text = "更新失败，仍显示上次比赛列表",
                                    color = Amber,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            if (state.matches.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    Text("暂无 ${filter.label} 比赛", color = TextLeague, fontSize = 14.sp)
                                }
                            } else {
                                MatchList(matches = state.matches, onMatchClick = onMatchClick)
                            }
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
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = if (isSelected) Amber else TabOff,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
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
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        var start = 0
        while (start < matches.size) {
            val date = matchDateKey(matches[start].time)
            var end = start + 1
            while (end < matches.size && matchDateKey(matches[end].time) == date) end++
            if (date != null) {
                val count = end - start
                item(key = "date-$start") { MatchDateHeader(date, count) }
            }
            items(matches.subList(start, end), key = { it.id }) { match ->
                ScoreboardCard(match = match, onClick = { onMatchClick(match) })
            }
            start = end
        }
    }
}

@Composable
private fun MatchDateHeader(date: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(date.replace("-", "月") + "日", color = TextTeam, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("共 $count 场比赛", color = TextTime, fontSize = 12.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = Divider1)
}

@Composable
private fun ScoreboardCard(match: Match, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "查看 ${match.homeTeam} 对 ${match.awayTeam} 的播放线路", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(match.league, color = TextLeague, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                text = match.time.substringAfter(' ', match.time),
                color = TextTeam,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            if (match.isLive) LiveIndicator(modifier = Modifier.align(Alignment.CenterEnd))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ScoreboardLogo(url = match.homeLogoUrl)
                Spacer(modifier = Modifier.width(8.dp))
                Text(match.homeTeam, color = TextTeam, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = match.score.takeIf { match.isLive && it.isNotBlank() } ?: "VS",
                color = if (match.isLive && match.score.isNotBlank()) Amber else TextTime,
                fontSize = if (match.isLive && match.score.isNotBlank()) 17.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End) {
                Text(match.awayTeam, color = TextTeam, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.width(8.dp))
                ScoreboardLogo(url = match.awayLogoUrl)
            }
        }

        if (match.streamUrls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextStream, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("${match.streamUrls.size} 条播放线路", color = TextStream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextStream, modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = Divider1, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun LiveIndicator(modifier: Modifier = Modifier) {
    var dotVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            dotVisible = !dotVisible
        }
    }
    Row(
        modifier = modifier,
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
            fontSize = 11.sp,
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
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(42.dp)
    )
}

private fun matchDateKey(time: String): String? =
    time.substringBefore(' ').takeIf { MatchDatePattern.matches(it) }

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
        Text("比赛列表加载失败", color = TextTeam, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            text = message,
            color = TabOff,
            fontSize = 13.sp
        )
        Text(
            text = "当前节点：$currentDomain",
            color = TextStream,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber)
        ) {
            Text("重试", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (backupDomains.isNotEmpty()) {
            Text(
                text = "备用节点",
                color = TabOff,
                fontSize = 13.sp
            )
            backupDomains.forEach { domain ->
                OutlinedButton(
                    onClick = { onSwitchDomain(domain) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Divider1)
                ) {
                    Text(domain, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextStream)
                }
            }
        }
    }
}
