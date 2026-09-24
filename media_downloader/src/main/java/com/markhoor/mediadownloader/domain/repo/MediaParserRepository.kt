package com.markhoor.mediadownloader.domain.repo

import com.markhoor.mediadownloader.domain.models.MediaModel

/** Reads the media behind a link. */
internal interface MediaParserRepository {

    /**
     * The media behind [url], with every quality labelled and sized where possible.
     * Fails with a [com.markhoor.mediadownloader.domain.models.MediaParseException].
     */
    suspend fun parse(url: String): Result<MediaModel>
}
