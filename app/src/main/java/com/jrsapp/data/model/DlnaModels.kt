package com.jrsapp.data.model

data class DlnaDevice(
    val friendlyName: String,
    val locationUrl: String,
    val controlUrl: String,
    val serviceType: String,
    val udn: String,
    val manufacturer: String? = null,
    val modelName: String? = null
)
