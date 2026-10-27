package com.markhoor.mediadownloader.data.work

import com.markhoor.mediadownloader.domain.models.DownloadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building the scheduler is `WorkManager.getInstance`, and a device whose framework cannot carry
 * WorkManager answers it with an [Error] rather than an exception.
 *
 * Reported from the field in October 2026: an Android 14 build with no `JobScheduler.forNamespace`,
 * which WorkManager 2.12 calls on anything reporting SDK 34 with no fallback. `NoSuchMethodError`
 * is not an `Exception`, so it walked through every guard between here and the host app - the use
 * case's, the repository's - and killed the app where it had asked for a download. Reproduced on a
 * device by making this one call throw: the app died on `Download`, and lived once it refused.
 */
class DownloadSchedulerBuildTest {

    @Test
    fun `a framework missing what WorkManager calls becomes a refusal, not an Error`() {
        val platform = NoSuchMethodError(
            "No virtual method forNamespace(Ljava/lang/String;)Landroid/app/job/JobScheduler;"
        )

        val refusal = runCatching { DownloadScheduler.of { throw platform } }.exceptionOrNull()

        assertTrue(
            "An Error here reaches the host through every catch(Exception) on the way: $refusal",
            refusal is DownloadException.SchedulerUnavailable,
        )
        assertSame("The platform's own error is kept, not swallowed", platform, refusal?.cause)
        assertTrue(
            "The reason belongs in the message: ${refusal?.message}",
            refusal?.message?.contains("forNamespace") == true,
        )
    }

    @Test
    fun `only a linkage error is turned into a refusal`() {
        val thrown = runCatching {
            DownloadScheduler.of { throw IllegalStateException("WorkManager is not initialized") }
        }.exceptionOrNull()

        // Already an exception, and already reported as a failed download: wrapping it would only
        // hide what a host that has misconfigured WorkManager needs to read.
        assertEquals(IllegalStateException::class.java, thrown?.javaClass)
    }
}
