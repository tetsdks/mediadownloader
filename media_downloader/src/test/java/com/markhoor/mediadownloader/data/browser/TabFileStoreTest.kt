package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.domain.models.SavedTabModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The open tabs on disk: what comes back, and what happens when nothing good is there. */
@OptIn(ExperimentalCoroutinesApi::class)
class TabFileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.storeOver(file: File) =
        TabFileStore(fileOf = { file }, scope = this, json = HttpClientFactory.json)

    private fun tabsFile() = File(folder.newFolder("files"), Browser.TABS_FILE)

    @Test
    fun `the tabs come back as they were left`() = runTest(StandardTestDispatcher()) {
        val file = tabsFile()
        val kept = listOf(
            SavedTabModel("https://www.dailymotion.com/", "Dailymotion"),
            SavedTabModel("https://www.tiktok.com/", "TikTok", isActive = true),
        )

        storeOver(file).save(kept)
        advanceUntilIdle()

        assertTrue("the file is written where it was asked for", file.isFile)
        val later = storeOver(file)
        later.load()
        assertEquals(kept, later.saved())
    }

    @Test
    fun `nothing kept yet is no tabs, not a failure`() = runTest(StandardTestDispatcher()) {
        val store = storeOver(File(folder.newFolder("empty"), Browser.TABS_FILE))
        store.load()

        assertEquals(emptyList<SavedTabModel>(), store.saved())
    }

    /** A file half written by a process that was killed, or one from a version that wrote something else. */
    @Test
    fun `a file that cannot be read loses the tabs and nothing else`() = runTest(StandardTestDispatcher()) {
        val file = tabsFile()
        file.parentFile?.mkdirs()
        file.writeText("""[{"url":"https://www.tiktok.com/","tit""")

        val store = storeOver(file)
        store.load()

        assertEquals(emptyList<SavedTabModel>(), store.saved())

        // And it writes over the unreadable file rather than refusing to keep anything again.
        store.save(listOf(SavedTabModel("https://rumble.com/", "Rumble")))
        advanceUntilIdle()
        val later = storeOver(file)
        later.load()
        assertEquals(listOf(SavedTabModel("https://rumble.com/", "Rumble")), later.saved())
    }

    @Test
    fun `no more tabs are kept than may be open`() = runTest(StandardTestDispatcher()) {
        val file = tabsFile()
        val store = storeOver(file)

        store.save((1..Browser.MAX_TABS + 6).map { SavedTabModel("https://example.test/$it") })
        advanceUntilIdle()

        val later = storeOver(file)
        later.load()
        assertEquals(Browser.MAX_TABS, later.saved().size)
    }

    /** Asked for before the start-up read has run, a store reads the file itself rather than answering nothing. */
    @Test
    fun `the tabs are there even if nothing has read them yet`() = runTest(StandardTestDispatcher()) {
        val file = tabsFile()
        storeOver(file).save(listOf(SavedTabModel("https://vimeo.com/", "Vimeo", isActive = true)))
        advanceUntilIdle()

        assertEquals(
            listOf(SavedTabModel("https://vimeo.com/", "Vimeo", isActive = true)),
            storeOver(file).saved(),
        )
    }
}
