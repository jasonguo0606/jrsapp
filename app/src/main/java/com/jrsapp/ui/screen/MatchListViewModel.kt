package com.jrsapp.ui.screen

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
    data class Success(val matches: List<Match>) : MatchListUiState()
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

    init {
        loadMatches()
    }

    fun loadMatches() {
        _uiState.value = MatchListUiState.Loading
        viewModelScope.launch {
            repository.fetchAllMatches(_currentDomain.value)
                .onSuccess { matches ->
                    allMatches = matches.filter { MatchParser.isBasketball(it.league) }
                    applyFilter()
                }
                .onFailure {
                    _uiState.value = MatchListUiState.Error(
                        message = it.message ?: "未知错误",
                        currentDomain = _currentDomain.value,
                        backupDomains = MatchRepository.BACKUP_DOMAINS.filterNot { domain ->
                            domain.equals(_currentDomain.value, ignoreCase = true)
                        }
                    )
                }
        }
    }

    fun switchDomain(domain: String) {
        if (_currentDomain.value.equals(domain, ignoreCase = true)) return
        _currentDomain.value = domain
        loadMatches()
    }

    fun setFilter(f: LeagueFilter) {
        _filter.value = f
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = when (_filter.value) {
            LeagueFilter.NBA -> allMatches.filter { MatchParser.isNba(it.league) }
            LeagueFilter.CBA -> allMatches.filter { MatchParser.isCba(it.league) }
            LeagueFilter.ALL -> allMatches
        }
        _uiState.value = MatchListUiState.Success(filtered)
    }
}
