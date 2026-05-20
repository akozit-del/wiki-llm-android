package com.wikillm.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HfModel(
    val id: String,
    @SerialName("modelId") val modelId: String? = null,
    val likes: Long = 0,
    val downloads: Long = 0,
    val tags: List<String> = emptyList(),
    @SerialName("lastModified") val lastModified: String? = null,
)

@Serializable
data class HfFile(
    val type: String, // "file" or "directory"
    val path: String,
    val size: Long = 0,
    val oid: String? = null,
)
