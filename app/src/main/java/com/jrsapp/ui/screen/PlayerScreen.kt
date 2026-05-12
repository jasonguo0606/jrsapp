package com.jrsapp.ui.screen

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.jrsapp.data.model.Match
import com.jrsapp.data.model.VideoSource

private const val PLAYER_TAG = "NativePlayer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    match: Match,
    onBack: () -> Unit
) {
    val viewModel: PlayerViewModel = viewModel(key = match.id, factory = PlayerViewModelFactory(match))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "${match.homeTeam} vs ${match.awayTeam}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(text = match.league, fontSize = 12.sp, color = Color.Gray)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
        )

        NativePlayerSection(
            source = uiState.currentSource,
            resolving = uiState.loadingPlaybackPage || uiState.resolvingSource,
            errorMessage = uiState.errorMessage,
            onRetry = viewModel::retry
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "切换线路",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(match.streamUrls) { index, link ->
                val isSelected = index == uiState.selectedLineIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFFE88C23) else Color(0xFF1E1E3A))
                        .clickable { viewModel.selectLine(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = link.label,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        when {
            uiState.loadingPlaybackPage -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在加载该线路下的主播入口...",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            uiState.subLines.isNotEmpty() -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "选择主播/清晰度",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(uiState.subLines) { index, link ->
                        val isSelected = index == uiState.selectedSubLineIndex
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF4FC3F7) else Color(0xFF1E1E3A))
                                .clickable { viewModel.selectSubLine(index) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = link.label,
                                color = if (isSelected) Color.White else Color.Gray,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun NativePlayerSection(
    source: VideoSource?,
    resolving: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    ) {
        when {
            source != null -> NativePlayer(source = source)
            resolving -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFFE88C23)
            )
            errorMessage != null -> PlayerErrorView(
                message = errorMessage,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> Text(
                text = "暂无可播放视频源",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun NativePlayer(source: VideoSource) {
    val context = LocalContext.current
    val exoPlayer = remember(source.url, source.referer) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(buildPlayerRequestHeaders(source))

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                Log.d(
                    PLAYER_TAG,
                    "prepare source url=${source.url} referer=${source.referer} origin=${extractOrigin(source.referer)} type=${source.type}"
                )
                val mimeType = mediaMimeType(source)
                val mediaItemBuilder = MediaItem.Builder().setUri(source.url)
                mimeType?.let(mediaItemBuilder::setMimeType)
                setMediaItem(mediaItemBuilder.build())
                addListener(buildPlayerLogger(this))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun playbackStateName(state: Int): String =
    when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

private fun buildPlayerRequestHeaders(source: VideoSource): Map<String, String> =
    buildMap {
        source.referer?.let { put("Referer", it) }
        extractOrigin(source.referer)?.let { put("Origin", it) }
        put("Accept", "*/*")
        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        put("Connection", "keep-alive")
    }

private fun extractOrigin(referer: String?): String? =
    referer?.let {
        runCatching {
            val uri = Uri.parse(it)
            "${uri.scheme}://${uri.host}" + uri.port.takeIf { port -> port > 0 }?.let { port -> ":$port" }.orEmpty()
        }.getOrNull()
    }

private fun mediaMimeType(source: VideoSource): String? =
    when (source.type) {
        com.jrsapp.data.model.VideoSourceType.HLS -> "application/x-mpegURL"
        com.jrsapp.data.model.VideoSourceType.MP4 -> "video/mp4"
        com.jrsapp.data.model.VideoSourceType.FLV -> "video/x-flv"
        com.jrsapp.data.model.VideoSourceType.UNKNOWN -> null
    }

private fun buildPlayerLogger(player: ExoPlayer): Player.Listener =
    object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(
                PLAYER_TAG,
                "state=${playbackStateName(playbackState)} isPlaying=${player.isPlaying} playWhenReady=${player.playWhenReady}"
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(
                PLAYER_TAG,
                "playerError code=${error.errorCodeName} message=${error.message}",
                error
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(PLAYER_TAG, "isPlayingChanged=$isPlaying")
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(
                PLAYER_TAG,
                "videoSize=${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio}"
            )
        }
    }

@Composable
private fun PlayerErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = message, color = Color.Gray, fontSize = 14.sp)
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE88C23))
        ) {
            Text("重新解析")
        }
    }
}
