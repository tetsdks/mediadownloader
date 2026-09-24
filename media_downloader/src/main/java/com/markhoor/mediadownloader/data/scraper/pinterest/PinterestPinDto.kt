package com.markhoor.mediadownloader.data.scraper.pinterest

import kotlinx.serialization.Serializable

/** Pinterest's `CloseupPageQuery` answer, reduced to the fields that are read. */
@Serializable
internal data class PinterestPinResponseDto(val data: PinterestQueryDto? = null) {
    val pin: PinterestPinDto? get() = data?.v3GetPinQuery?.data
}

@Serializable
internal data class PinterestQueryDto(val v3GetPinQuery: PinterestPinQueryDto? = null)

@Serializable
internal data class PinterestPinQueryDto(val data: PinterestPinDto? = null)

@Serializable
internal data class PinterestPinDto(
    val autoAltText: String? = null,
    val images: PinterestImageDto? = null,
    val videos: PinterestVideosDto? = null,
    val storyPinData: PinterestStoryDto? = null,
) {
    /**
     * The pin's video: its own stream, or the first video block anywhere in an idea pin's pages -
     * a story's video does not have to be on its first page.
     */
    val video: PinterestStreamDto?
        get() = videos?.videoList?.vHLSV4?.takeIf { !it.url.isNullOrBlank() }
            ?: storyPinData?.pages.orEmpty()
                .flatMap { it.blocks.orEmpty() }
                .firstNotNullOfOrNull { it.videoDataV2?.videoListMobile?.vHLSV3MOBILE?.takeIf { s -> !s.url.isNullOrBlank() } }
}

@Serializable
internal data class PinterestImageDto(val url: String? = null)

@Serializable
internal data class PinterestVideosDto(val videoList: PinterestVideoListDto? = null)

@Serializable
internal data class PinterestVideoListDto(val vHLSV4: PinterestStreamDto? = null)

@Serializable
internal data class PinterestStoryDto(val pages: List<PinterestStoryPageDto>? = null)

@Serializable
internal data class PinterestStoryPageDto(val blocks: List<PinterestStoryBlockDto>? = null)

@Serializable
internal data class PinterestStoryBlockDto(val videoDataV2: PinterestStoryVideoDto? = null)

@Serializable
internal data class PinterestStoryVideoDto(val videoListMobile: PinterestMobileVideoListDto? = null)

@Serializable
internal data class PinterestMobileVideoListDto(val vHLSV3MOBILE: PinterestStreamDto? = null)

/** An HLS master and the frame Pinterest renders for it. */
@Serializable
internal data class PinterestStreamDto(
    val url: String? = null,
    val thumbnail: String? = null,
)
