package com.jrsapp.data.model

data class PlaybackPage(
    val pageUrl: String,
    val subLines: List<StreamLink>
)

enum class VideoSourceType {
    HLS,
    FLV,
    MP4,
    UNKNOWN
}

data class VideoSource(
    val url: String,
    val type: VideoSourceType,
    val label: String,
    val referer: String? = null
)
