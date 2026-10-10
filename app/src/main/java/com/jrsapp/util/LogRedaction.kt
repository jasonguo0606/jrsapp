package com.jrsapp.util

fun redactedUrl(url: String?): String = url.orEmpty().substringBefore('?').substringBefore('#').take(200)
