package com.markhoor.mediadownloader.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler reaches the repository as a [Lazy], so that reading the downloads cannot build it.
 *
 * Building it is `WorkManager.getInstance`, which initialises WorkManager, and a host that has
 * removed `WorkManagerInitializer` - which is what implementing `Configuration.Provider` asks for,
 * and what the app this was reported from does - gets that initialisation on whichever call comes
 * first. In October 2026 that call was the host collecting `observeDownloads` at startup to count
 * finished downloads, on an Android 14 build whose framework has no `JobScheduler.forNamespace`:
 * WorkManager 2.12.0 calls it on anything reporting SDK 34 with no fallback, so the app died with
 * `NoSuchMethodError` seven times before anyone had asked for a download at all.
 *
 * Reading the list is Room and nothing else. This holds the wiring to that, which is all a test
 * without a `Context` can hold it to: the three collaborators the read path never touches cannot
 * be built here, and Kotlin refuses to let them be left out.
 */
class DownloadRepositorySchedulerTest {

    @Test
    fun `the repository is handed its scheduler lazily, not built`() {
        val parameters = DownloadRepositoryImpl::class.java.declaredConstructors
            .single { it.parameterCount > 1 }
            .parameterTypes
            .toList()

        assertTrue(
            "The scheduler must arrive as a Lazy - a DownloadScheduler here means reading the " +
                "downloads builds one, and building one initialises WorkManager. " +
                "Parameters were: ${parameters.map { it.simpleName }}",
            parameters.any { it == Lazy::class.java },
        )
        assertTrue(
            "No parameter may be a DownloadScheduler itself. " +
                "Parameters were: ${parameters.map { it.simpleName }}",
            parameters.none { it.simpleName == "DownloadScheduler" },
        )
    }
}
