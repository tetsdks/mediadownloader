package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ParseLinkUseCaseTest {

    private class FakeRepository(private val answer: (String) -> Result<MediaModel>) : MediaParserRepository {
        val asked = mutableListOf<String>()
        override suspend fun parse(url: String): Result<MediaModel> = answer(url).also { asked += url }
    }

    private val media = MediaModel(title = "t", thumbnailUrl = null, qualities = emptyList(), sourceUrl = "s")

    private fun useCase(repository: MediaParserRepository, strict: Boolean = true) =
        ParseLinkUseCase(CheckSiteAccessUseCase(strict, emptySet()), repository)

    @Test
    fun `a blocked site is refused without being fetched`() = runTest {
        val repository = FakeRepository { Result.success(media) }
        val result = useCase(repository)("https://www.pornhub.com/view_video.php?viewkey=abc")
        assertTrue(result.exceptionOrNull() is MediaParseException.SiteBlocked)
        assertTrue(repository.asked.isEmpty())
    }

    @Test
    fun `an unsupported site is refused in strict mode without being fetched`() = runTest {
        val repository = FakeRepository { Result.success(media) }
        val result = useCase(repository)("https://stackoverflow.com/questions/1")
        assertTrue(result.exceptionOrNull() is MediaParseException.SiteUnsupported)
        assertTrue(repository.asked.isEmpty())
    }

    @Test
    fun `an allowed link is read from the text it was shared in`() = runTest {
        val repository = FakeRepository { Result.success(media) }
        val result = useCase(repository)("look at this https://www.pinterest.com/pin/123/ so cool")
        assertEquals(media, result.getOrThrow())
        assertEquals(listOf("https://www.pinterest.com/pin/123/"), repository.asked)
    }

    @Test
    fun `any other failure arrives as media not found`() = runTest {
        val repository = FakeRepository { Result.failure(IOException("offline")) }
        val error = useCase(repository)("https://www.pinterest.com/pin/123/").exceptionOrNull()
        assertTrue(error is MediaParseException.MediaNotFound)
        assertTrue(error?.cause is IOException)
    }
}
