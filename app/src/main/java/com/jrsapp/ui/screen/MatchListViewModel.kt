package com.jrsapp.ui.screen

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrsapp.data.model.Match
import com.jrsapp.data.parser.MatchParser
import com.jrsapp.data.repository.MatchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LeagueFilter(val label: String) {
    ALL("全部篮球"),
    NBA("NBA"),
    CBA("CBA")
}

sealed class MatchListUiState {
    object Loading : MatchListUiState()
    data class Success(
        val matches: List<Match>,
        val updatedAtMillis: Long,
        val refreshing: Boolean = false,
        val refreshError: String? = null
    ) : MatchListUiState()
    data class Error(
        val message: String,
        val currentDomain: String,
        val backupDomains: List<String>
    ) : MatchListUiState()
}

class MatchListViewModel(
    private val repository: MatchRepository = MatchRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<MatchListUiState>(MatchListUiState.Loading)
    val uiState: StateFlow<MatchListUiState> = _uiState

    private val _filter = MutableStateFlow(LeagueFilter.ALL)
    val filter: StateFlow<LeagueFilter> = _filter

    private val _currentDomain = MutableStateFlow(MatchRepository.PRIMARY_DOMAIN)
    val currentDomain = _currentDomain.asStateFlow()

    // 缓存全部比赛，切换 tab 时不重新请求
    private var allMatches: List<Match> = emptyList()
    private var lastRefreshElapsedMillis = 0L
    private var lastSuccessDomain: String? = null
    private var loadGeneration = 0
    private var loadingDomain: String? = null

    init {
        loadMatches()
    }

    fun loadMatches() {
        val domain = _currentDomain.value
        if (loadingDomain == domain) return
        val generation = ++loadGeneration
        loadingDomain = domain
        _uiState.value = when (val current = _uiState.value) {
            is MatchListUiState.Success -> if (lastSuccessDomain == domain) {
                current.copy(refreshing = true, refreshError = null)
            } else MatchListUiState.Loading
            else -> MatchListUiState.Loading
        }
        viewModelScope.launch {
            repository.fetchAllMatches(domain)
                .onSuccess { matches ->
                    if (generation != loadGeneration) return@onSuccess
                    allMatches = matches.filter { MatchParser.isBasketball(it.league) }
                    lastRefreshElapsedMillis = SystemClock.elapsedRealtime()
                    lastSuccessDomain = domain
                    _uiState.value = MatchListUiState.Success(
                        matches = emptyList(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    applyFilter()
                }
                .onFailure {
                    if (generation != loadGeneration) return@onFailure
                    _uiState.value = when (val current = _uiState.value) {
                        is MatchListUiState.Success -> current.copy(
                            refreshing = false,
                            refreshError = it.message ?: "更新失败"
                        )
                        else -> MatchListUiState.Error(
                            message = it.message ?: "未知错误",
                            currentDomain = domain,
                            backupDomains = MatchRepository.BACKUP_DOMAINS.filterNot { backup ->
                                backup.equals(domain, ignoreCase = true)
                            }
                        )
                    }
                }
            if (generation == loadGeneration) loadingDomain = null
        }
    }

    fun refreshIfStale() {
        if (loadingDomain != null) return
        if (lastRefreshElapsedMillis == 0L ||
            SystemClock.elapsedRealtime() - lastRefreshElapsedMillis >= REFRESH_INTERVAL_MILLIS
        ) loadMatches()
    }

    fun switchDomain(domain: String) {
        if (_currentDomain.value.equals(domain, ignoreCase = true)) return
        _currentDomain.value = domain
        loadMatches()
    }

    fun setFilter(f: LeagueFilter) {
        _filter.value = f
        if (_uiState.value is MatchListUiState.Success) applyFilter()
    }

    private fun applyFilter() {
        val filtered = when (_filter.value) {
            LeagueFilter.NBA -> allMatches.filter { MatchParser.isNba(it.league) }
            LeagueFilter.CBA -> allMatches.filter { MatchParser.isCba(it.league) }
            LeagueFilter.ALL -> allMatches
        }
        _uiState.value = when (val current = _uiState.value) {
            is MatchListUiState.Success -> current.copy(matches = filtered)
            else -> MatchListUiState.Success(filtered, updatedAtMillis = System.currentTimeMillis())
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 60_000L
    }
}
