package com.jrsapp.data.model

data class Match(
    val id: String,
    val league: String,
    val time: String,
    val homeTeam: String,
    val homeLogoUrl: String,
    val awayTeam: String,
    val awayLogoUrl: String,
    val score: String,
    val isLive: Boolean,
    val streamUrls: List<StreamLink>
)

data class StreamLink(
    val label: String,
    val url: String
)
