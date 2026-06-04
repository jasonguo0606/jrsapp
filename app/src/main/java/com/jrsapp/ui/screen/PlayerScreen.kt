package com.jrsapp.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.ui.AspectRatioFrameLayout
import com.jrsapp.data.model.Match
import com.jrsapp.data.model.VideoSource
import com.jrsapp.data.repository.DlnaRepository
import kotlinx.coroutines.delay

private const val PLAYER_TAG = "NativePlayer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    match: Match,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dlnaRepository = remember(context) { DlnaRepository(context) }
    val viewModel: PlayerViewModel = viewModel(
        key = match.id,
        factory = PlayerViewModelFactory(match, dlnaRepository)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isFullscreenState = rememberSaveable { mutableStateOf(false) }
    val isFullscreen = isFullscreenState.value
    val activity = context.findActivity()
    var showCastDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.castMessage) {
        if (uiState.castMessage != null) {
            delay(2200)
            viewModel.clearCastMessage()
        }
    }

    DisposableEffect(activity, isFullscreen) {
        activity?.requestedOrientation =
            if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (isFullscreen) {
            activity?.enterImmersiveMode()
        } else {
            activity?.exitImmersiveMode()
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.exitImmersiveMode()
        }
    }

    BackHandler {
        if (isFullscreen) {
            isFullscreenState.value = false
        } else {
            onBack()
        }
    }

    if (showCastDialog) {
        CastDeviceDialog(
            devices = uiState.dlnaDevices,
            discovering = uiState.discoveringDevices,
            currentDevice = uiState.selectedDlnaDevice?.friendlyName,
            onDismiss = { showCastDialog = false },
            onRefresh = viewModel::discoverDevices,
            onSelect = { device ->
                viewModel.castToDevice(device)
                showCastDialog = false
            }
        )
    }

    if (isFullscreen) {
        FullscreenPlayerLayout(
            source = uiState.currentSource,
            resolving = uiState.loadingPlaybackPage || uiState.resolvingSource,
            errorMessage = uiState.errorMessage,
            onRetry = viewModel::retry,
            onExitFullscreen = { isFullscreenState.value = false },
            isCasting = uiState.selectedDlnaDevice != null
        )
        return
    }

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
            actions = {
                IconButton(
                    onClick = {
                        showCastDialog = true
                        viewModel.discoverDevices()
                    }
                ) {
                    Icon(
                        Icons.Default.Cast,
                        contentDescription = "投屏",
                        tint = if (uiState.selectedDlnaDevice != null) Color(0xFF4FC3F7) else Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
        )

        if (uiState.selectedDlnaDevice != null || uiState.castMessage != null) {
            CastStatusCard(
                deviceName = uiState.selectedDlnaDevice?.friendlyName,
                message = uiState.castMessage,
                busy = uiState.castingInProgress,
                onStop = viewModel::stopCasting,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        NativePlayerSection(
            source = uiState.currentSource,
            resolving = uiState.loadingPlaybackPage || uiState.resolvingSource,
            errorMessage = uiState.errorMessage,
            onRetry = viewModel::retry,
            fullscreen = false,
            onToggleFullscreen = { isFullscreenState.value = true },
            isCasting = uiState.selectedDlnaDevice != null
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
                    text = "正在加载该线路下的直播线路...",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            uiState.subLines.isNotEmpty() -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "选择直播线路",
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
    onRetry: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    isCasting: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (fullscreen) 0.dp else 16.dp),
        shape = RoundedCornerShape(0.dp),
        color = Color(0xFF070B14),
        tonalElevation = 0.dp,
        shadowElevation = if (fullscreen) 0.dp else 10.dp,
        border = if (fullscreen) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = if (fullscreen) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            }
        ) {
            when {
                source != null -> NativePlayer(
                    source = source,
                    fullscreen = fullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    isCasting = isCasting,
                    modifier = Modifier.fillMaxSize()
                )
                resolving -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFFF9B3D)
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
}

@Composable
private fun NativePlayer(
    source: VideoSource,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    isCasting: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context.findActivity()
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
    var keepScreenOn by remember(exoPlayer) { mutableStateOf(exoPlayer.shouldKeepScreenOnForPlayback()) }

    LaunchedEffect(isCasting, exoPlayer) {
        if (isCasting) {
            exoPlayer.pause()
        } else if (exoPlayer.playbackState != Player.STATE_ENDED) {
            exoPlayer.play()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer, activity) {
        val syncKeepScreenOn = {
            keepScreenOn = exoPlayer.shouldKeepScreenOnForPlayback()
            activity?.setPlaybackKeepScreenOn(keepScreenOn)
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                syncKeepScreenOn()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncKeepScreenOn()
            }
        }
        exoPlayer.addListener(listener)
        syncKeepScreenOn()
        onDispose {
            exoPlayer.removeListener(listener)
            activity?.setPlaybackKeepScreenOn(false)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
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

        PlayerControlOverlay(
            exoPlayer = exoPlayer,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            isCasting = isCasting,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PlayerControlOverlay(
    exoPlayer: ExoPlayer,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    isCasting: Boolean,
    modifier: Modifier = Modifier
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { controlsVisible = !controlsVisible }
            )
        }
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0x80000000), CircleShape)
                    .clickable {
                        if (isCasting) return@clickable
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = if (exoPlayer.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(if (exoPlayer.isPlaying) 0.dp else 32.dp)
                )
                if (exoPlayer.isPlaying) {
                    Text(
                        text = "II",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControlBar(
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen
            )
        }
    }
}

@Composable
private fun BottomControlBar(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier.size(48.dp)
        ) {
            Text(
                text = if (fullscreen) "退出" else "全屏",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun FullscreenPlayerLayout(
    source: VideoSource?,
    resolving: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onExitFullscreen: () -> Unit,
    isCasting: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        NativePlayerSection(
            source = source,
            resolving = resolving,
            errorMessage = errorMessage,
            onRetry = onRetry,
            fullscreen = true,
            onToggleFullscreen = onExitFullscreen,
            isCasting = isCasting
        )

        IconButton(
            onClick = onExitFullscreen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.46f))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "退出全屏",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun CastStatusCard(
    deviceName: String?,
    message: String?,
    busy: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF13293D),
        border = BorderStroke(1.dp, Color(0xFF2E5D7B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = null,
                tint = Color(0xFF7FD3FF)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName?.let { "正在投屏到 $it" } ?: "投屏状态",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                val detail = when {
                    busy -> "正在和设备通信..."
                    !message.isNullOrBlank() -> message
                    else -> "电视端正在拉取当前线路"
                }
                Text(
                    text = detail,
                    color = Color(0xFFB7CCE0),
                    fontSize = 12.sp
                )
            }
            if (deviceName != null) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = !busy,
                    border = BorderStroke(1.dp, Color(0xFF7FD3FF))
                ) {
                    Text(text = "停止", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CastDeviceDialog(
    devices: List<com.jrsapp.data.model.DlnaDevice>,
    discovering: Boolean,
    currentDevice: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (com.jrsapp.data.model.DlnaDevice) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121A2A),
        title = {
            Text(text = "选择投屏设备", color = Color.White)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (currentDevice != null) {
                    Text(
                        text = "当前设备：$currentDevice",
                        color = Color(0xFF7FD3FF),
                        fontSize = 12.sp
                    )
                }
                if (discovering) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFFF9B3D)
                        )
                        Text(text = "正在搜索同一 Wi-Fi 下的电视...", color = Color.Gray, fontSize = 13.sp)
                    }
                }
                if (!discovering && devices.isEmpty()) {
                    Text(text = "暂未发现设备，可以刷新后重试。", color = Color.Gray, fontSize = 13.sp)
                }
                devices.forEach { device ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(device) },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1B2840),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                text = device.friendlyName,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            val subtitle = listOfNotNull(device.manufacturer, device.modelName).joinToString(" / ")
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("刷新", color = Color.White)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE88C23))
            ) {
                Text("关闭")
            }
        }
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

private fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.enterImmersiveMode() {
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
}

private fun Activity.exitImmersiveMode() {
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
}

private fun ExoPlayer.shouldKeepScreenOnForPlayback(): Boolean =
    playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED

private fun Activity.setPlaybackKeepScreenOn(enabled: Boolean) {
    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
