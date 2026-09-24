package com.markhoor.mediadownloader.domain.models

/** What a piece of media is. */
enum class MediaType { Video, Image, Audio }

/**
 * Media found behind a link, ready to be offered.
 *
 * @param title the post's own name, cleaned; empty when the site gives none.
 * @param thumbnailUrl artwork to show while the media is offered, when the site has any.
 * @param qualities every downloadable version, in the order they should be listed.
 * @param sourceUrl the link this media was found behind.
 * @param durationMillis how long a video or audio runs, when the site says.
 */
data class MediaModel(
    val title: String,
    val thumbnailUrl: String?,
    val qualities: List<MediaQualityModel>,
    val sourceUrl: String,
    val durationMillis: Long? = null,
)

/**
 * One downloadable version of a piece of media.
 *
 * @param url the file or HLS playlist to download.
 * @param label what the quality is called, e.g. `720p`, `HD`, `Page 2`.
 * @param sizeBytes the download's size when it is known or can be estimated reliably.
 * @param audioUrl the playlist holding the sound, for a stream that keeps it apart from the picture.
 * @param headers request headers the download needs, e.g. a `Referer` the CDN insists on.
 */
data class MediaQualityModel(
    val url: String,
    val label: String,
    val type: MediaType,
    val sizeBytes: Long? = null,
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)
