package com.markhoor.mediadownloader.data.storage

import com.markhoor.mediadownloader.domain.models.StorageRefusal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reasoning behind a refusal. The probe itself needs a device - what matters here is that each
 * cause is told apart, because the host fixes each one differently, and only one of them is
 * something to ask the user about.
 */
class DownloadStorageTest {

    @Test
    fun `up to android 10 a missing permission is the reason`() {
        listOf(24, 28, 29).forEach { sdk ->
            assertEquals(
                "sdk $sdk",
                StorageRefusal.PermissionNotGranted,
                storageRefusalFor(sdk, writePermissionGranted = false, legacyStorage = true),
            )
        }
    }

    @Test
    fun `android 10 with the permission and no legacy mode is the manifest, not the user`() {
        // The trap a permission check alone misses: granted, and the folder still refuses.
        assertEquals(
            StorageRefusal.LegacyStorageDisabled,
            storageRefusalFor(29, writePermissionGranted = true, legacyStorage = false),
        )
    }

    @Test
    fun `a granted permission is never reported as missing`() {
        // What the user would be told is "grant the permission" for something they already granted.
        listOf(24, 28, 29, 30, 33).forEach { sdk ->
            listOf(true, false).forEach { legacy ->
                assertEquals(
                    "sdk $sdk legacy $legacy",
                    false,
                    storageRefusalFor(sdk, writePermissionGranted = true, legacyStorage = legacy) ==
                        StorageRefusal.PermissionNotGranted,
                )
            }
        }
    }

    @Test
    fun `android 10 in legacy mode with the permission leaves the storage itself`() {
        assertEquals(
            StorageRefusal.StorageUnavailable,
            storageRefusalFor(29, writePermissionGranted = true, legacyStorage = true),
        )
    }

    @Test
    fun `below android 10 a granted permission leaves the storage itself`() {
        assertEquals(
            StorageRefusal.StorageUnavailable,
            storageRefusalFor(28, writePermissionGranted = true, legacyStorage = true),
        )
    }

    @Test
    fun `android 11 and later need no permission, so a refusal is the storage`() {
        listOf(30, 33, 36).forEach { sdk ->
            // Not PermissionNotGranted: asking for a permission that does nothing there would be
            // a dialog the user cannot act on. Legacy mode is not theirs either.
            assertEquals(
                "sdk $sdk",
                StorageRefusal.StorageUnavailable,
                storageRefusalFor(sdk, writePermissionGranted = false, legacyStorage = false),
            )
        }
    }
}
