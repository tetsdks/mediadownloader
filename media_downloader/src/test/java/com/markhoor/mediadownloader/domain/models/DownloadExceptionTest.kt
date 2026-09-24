package com.markhoor.mediadownloader.domain.models

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused download is read by whoever integrates the module, so each message has to name the fix
 * rather than the symptom.
 */
class DownloadExceptionTest {

    private fun messageFor(refusal: StorageRefusal): String =
        DownloadException.StorageNotWritable("/sdcard/Download/App", refusal).message.orEmpty()

    @Test
    fun `a missing permission names the permission to ask for`() {
        val message = messageFor(StorageRefusal.PermissionNotGranted)
        assertTrue(message, message.contains("WRITE_EXTERNAL_STORAGE"))
        assertTrue(message, message.contains("/sdcard/Download/App"))
    }

    @Test
    fun `android 10 names the manifest flag only the host can set`() {
        val message = messageFor(StorageRefusal.LegacyStorageDisabled)
        assertTrue(message, message.contains("requestLegacyExternalStorage"))
        // And says plainly that the permission is not the problem, so nobody turns this into a
        // permission prompt for a user who already granted it.
        assertTrue(message, message.contains("granted"))
        assertTrue(message, message.contains("cannot fix"))
    }

    @Test
    fun `storage that is simply not there asks for nothing`() {
        val message = messageFor(StorageRefusal.StorageUnavailable)
        assertTrue(message, message.contains("no external storage") || message.contains("no room"))
        // Nothing to grant, so naming a permission here would send the host after the wrong fix.
        assertTrue(message, !message.contains("WRITE_EXTERNAL_STORAGE"))
    }
}
