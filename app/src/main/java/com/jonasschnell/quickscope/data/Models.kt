package com.jonasschnell.quickscope.data

import kotlinx.serialization.Serializable

@Serializable
data class Me(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val pictureUrl: String? = null,
)

@Serializable
data class SessionResponse(val token: String)

@Serializable
data class AppRelease(val versionCode: Int, val versionName: String, val notes: String? = null, val size: Long = 0, val sha256: String = "")
