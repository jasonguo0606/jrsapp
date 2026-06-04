package com.jrsapp.ui.screen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jrsapp.data.model.DlnaDevice
import com.jrsapp.data.model.Match
import com.jrsapp.data.model.StreamLink
import com.jrsapp.data.model.VideoSource
import com.jrsapp.data.repository.DlnaRepository
import com.jrsapp.data.repository.PlaybackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val selectedLineIndex: Int = 0,
    val selectedSubLineIndex: Int = -1,
    val loadingPlaybackPage: Boolean = false,
    val resolvingSource: Boolean = false,
    val subLines: List<StreamLink> = emptyList(),
    val currentSource: VideoSource? = null,
    val resolvedSources: List<VideoSource> = emptyList(),
    val errorMessage: String? = null,
    val dlnaDevices: List<DlnaDevice> = emptyList(),
    val discoveringDevices: Boolean = false,
    val selectedDlnaDevice: DlnaDevice? = null,
    val castingInProgress: Boolean = false,
    val castMessage: String? = null
)

class PlayerViewModel(
    private val match: Match,
    private val playbackRepository: PlaybackRepository = PlaybackRepository(),
    private val dlnaRepository: DlnaRepository
) : ViewModel() {

    private companion object {
        const val TAG = "PlayerViewModel"
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        loadLinePage(0)
    }

    fun discoverDevices() {
        if (_uiState.value.discoveringDevices) return
        _uiState.value = _uiState.value.copy(
            discoveringDevices = true,
            castMessage = null
        )
        viewModelScope.launch {
            dlnaRepository.discoverDevices()
                .onSuccess { devices ->
                    _uiState.value = _uiState.value.copy(
                        discoveringDevices = false,
                        dlnaDevices = devices,
                        castMessage = if (devices.isEmpty()) "未发现可用投屏设备，请确认电视与手机在同一 Wi-Fi" else null
                    )
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        discoveringDevices = false,
                        castMessage = throwable.message ?: "搜索投屏设备失败"
                    )
                }
        }
    }

    fun castToDevice(device: DlnaDevice) {
        val source = _uiState.value.currentSource
        if (source == null) {
            _uiState.value = _uiState.value.copy(
                castMessage = "当前还没有可投屏的视频源"
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            castingInProgress = true,
            castMessage = null
        )
        viewModelScope.launch {
            dlnaRepository.startCasting(
                device = device,
                source = source,
                title = "${match.homeTeam} vs ${match.awayTeam}"
            ).onSuccess {
                _uiState.value = _uiState.value.copy(
                    selectedDlnaDevice = device,
                    castingInProgress = false,
                    castMessage = "正在投屏到 ${device.friendlyName}"
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    castingInProgress = false,
                    castMessage = throwable.message ?: "投屏失败"
                )
            }
        }
    }

    fun stopCasting() {
        val device = _uiState.value.selectedDlnaDevice ?: return
        _uiState.value = _uiState.value.copy(
            castingInProgress = true,
            castMessage = null
        )
        viewModelScope.launch {
            dlnaRepository.stopCasting(device)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        selectedDlnaDevice = null,
                        castingInProgress = false,
                        castMessage = "已停止投屏"
                    )
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        castingInProgress = false,
                        castMessage = throwable.message ?: "停止投屏失败"
                    )
                }
        }
    }

    fun clearCastMessage() {
        _uiState.value = _uiState.value.copy(castMessage = null)
    }

    fun selectLine(index: Int) {
        if (index == _uiState.value.selectedLineIndex) return
        loadLinePage(index)
    }

    fun selectSubLine(index: Int) {
        if (index == _uiState.value.selectedSubLineIndex) return
        resolveSubLine(index)
    }

    fun retry() {
        if (_uiState.value.selectedSubLineIndex >= 0 && _uiState.value.subLines.isNotEmpty()) {
            resolveSubLine(_uiState.value.selectedSubLineIndex)
        } else {
            loadLinePage(_uiState.value.selectedLineIndex)
        }
    }

    private fun loadLinePage(index: Int) {
        val line = match.streamUrls.getOrNull(index)
        Log.d(TAG, "loadLinePage index=$index line=${line?.label} url=${line?.url}")
        _uiState.value = _uiState.value.copy(
            selectedLineIndex = index,
            selectedSubLineIndex = -1,
            loadingPlaybackPage = true,
            resolvingSource = false,
            subLines = emptyList(),
            currentSource = null,
            resolvedSources = emptyList(),
            errorMessage = null
        )

        if (line == null) {
            _uiState.value = _uiState.value.copy(
                loadingPlaybackPage = false,
                errorMessage = "未找到对应直播线路"
            )
            return
        }

        viewModelScope.launch {
            playbackRepository.loadPlaybackPage(line.url)
                .onSuccess { page ->
                    Log.d(TAG, "loadLinePage success index=$index subLines=${page.subLines}")
                    if (page.subLines.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            loadingPlaybackPage = false
                        )
                        resolvePrimaryLineDirectly(index, line.url)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            loadingPlaybackPage = false,
                            subLines = page.subLines,
                            selectedSubLineIndex = 0
                        )
                        resolveSubLine(0)
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "loadLinePage failure index=$index url=${line.url}", throwable)
                    _uiState.value = _uiState.value.copy(
                        loadingPlaybackPage = false,
                        errorMessage = throwable.message ?: "加载线路页面失败"
                    )
                }
        }
    }

    private fun resolvePrimaryLineDirectly(index: Int, url: String) {
        Log.d(TAG, "resolvePrimaryLineDirectly index=$index url=$url")
        _uiState.value = _uiState.value.copy(
            resolvingSource = true,
            currentSource = null,
            resolvedSources = emptyList(),
            errorMessage = null
        )

        viewModelScope.launch {
            playbackRepository.resolveVideoSources(url)
                .onSuccess { sources ->
                    applyResolvedSources(index = index, label = "primary", url = url, sources = sources)
                }
                .onFailure { throwable ->
                    Log.e(TAG, "resolvePrimaryLineDirectly failure index=$index url=$url", throwable)
                    _uiState.value = _uiState.value.copy(
                        resolvingSource = false,
                        errorMessage = throwable.message ?: "解析播放源失败"
                    )
                }
        }
    }

    private fun resolveSubLine(index: Int) {
        val subLine = _uiState.value.subLines.getOrNull(index)
        Log.d(TAG, "resolveSubLine index=$index line=${subLine?.label} url=${subLine?.url}")
        _uiState.value = _uiState.value.copy(
            selectedSubLineIndex = index,
            resolvingSource = true,
            currentSource = null,
            resolvedSources = emptyList(),
            errorMessage = null
        )

        if (subLine == null) {
            _uiState.value = _uiState.value.copy(
                resolvingSource = false,
                errorMessage = "未找到对应直播线路"
            )
            return
        }

        viewModelScope.launch {
            playbackRepository.resolveVideoSources(subLine.url)
                .onSuccess { sources ->
                    applyResolvedSources(index = index, label = subLine.label, url = subLine.url, sources = sources)
                }
                .onFailure { throwable ->
                    Log.e(TAG, "resolveSubLine failure index=$index url=${subLine.url}", throwable)
                    _uiState.value = _uiState.value.copy(
                        resolvingSource = false,
                        errorMessage = throwable.message ?: "解析播放源失败"
                    )
                }
        }
    }

    private fun applyResolvedSources(
        index: Int,
        label: String,
        url: String,
        sources: List<VideoSource>
    ) {
        Log.d(TAG, "resolve success index=$index label=$label url=$url sources=${sources.map { it.url }}")
        _uiState.value = if (sources.isEmpty()) {
            Log.d(TAG, "resolve empty sources index=$index label=$label")
            _uiState.value.copy(
                resolvingSource = false,
                errorMessage = "当前线路未解析到可播放视频源"
            )
        } else {
            _uiState.value.copy(
                resolvingSource = false,
                currentSource = sources.first(),
                resolvedSources = sources
            ).also { state ->
                recastIfNeeded(state.selectedDlnaDevice, sources.first())
            }
        }
    }

    private fun recastIfNeeded(device: DlnaDevice?, source: VideoSource) {
        if (device == null) return
        _uiState.value = _uiState.value.copy(
            castingInProgress = true,
            castMessage = "线路已切换，正在重新投屏到 ${device.friendlyName}"
        )
        viewModelScope.launch {
            dlnaRepository.startCasting(
                device = device,
                source = source,
                title = "${match.homeTeam} vs ${match.awayTeam}"
            ).onSuccess {
                _uiState.value = _uiState.value.copy(
                    castingInProgress = false,
                    castMessage = "正在投屏到 ${device.friendlyName}"
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    selectedDlnaDevice = null,
                    castingInProgress = false,
                    castMessage = throwable.message ?: "重新投屏失败"
                )
            }
        }
    }
}

class PlayerViewModelFactory(
    private val match: Match,
    private val dlnaRepository: DlnaRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(
            match = match,
            dlnaRepository = dlnaRepository
        ) as T
    }
}
