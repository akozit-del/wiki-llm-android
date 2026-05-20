package com.wikillm.android.data

/** One entry from the Kiwix download index */
data class KiwixEntry(
    val filename: String,
    val sizeBytes: Long,
    val date: String,
    val downloadUrl: String,
    val language: String?,   // "en", "ru", "fr" (from filename)
    val topic: String?,      // "all", "medicine", ... (from filename)
    val variant: String?,    // "maxi", "mini", "nopic" (from filename)
)

/** ZIM file picked from user's device via SAF */
data class SelectedZim(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
)

/** ZIM file that has been downloaded into app storage */
data class DownloadedZim(
    val filename: String,
    val absolutePath: String,
    val sizeBytes: Long,
)
