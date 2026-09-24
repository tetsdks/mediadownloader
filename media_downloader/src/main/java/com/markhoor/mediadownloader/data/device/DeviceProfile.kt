package com.markhoor.mediadownloader.data.device

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import com.markhoor.mediadownloader.PerformanceMode
import com.markhoor.mediadownloader.core.Constants.Device
import com.markhoor.mediadownloader.core.Constants.Network

/**
 * What the device can take. Decided once, at start: a low-end device gets fewer parallel requests,
 * slower page scans and no button animations, so the browser and the downloads stay smooth there.
 */
internal class DeviceProfile(val isLowEnd: Boolean) {

    /** The most one page or api answer may take in memory: the tier's cap, never over a share of the heap. */
    val maxResponseBytes: Long
        get() = minOf(
            if (isLowEnd) Device.LOW_END_MAX_RESPONSE_BYTES else Network.MAX_RESPONSE_BYTES,
            Runtime.getRuntime().maxMemory() / Device.HEAP_SHARE_PER_RESPONSE,
        )

    companion object {
        fun of(context: Context, mode: PerformanceMode): DeviceProfile = when (mode) {
            PerformanceMode.Standard -> DeviceProfile(isLowEnd = false)
            PerformanceMode.LowEnd -> DeviceProfile(isLowEnd = true)
            PerformanceMode.Auto -> DeviceProfile(isLowEnd = detectLowEnd(context))
        }

        /**
         * The platform's own verdict first, then the facts behind it: a small heap, little memory,
         * or few cores. Any one is enough - each alone is what makes a busy page stutter.
         */
        private fun detectLowEnd(context: Context): Boolean {
            val activityManager = context.getSystemService<ActivityManager>() ?: return false
            if (activityManager.isLowRamDevice) return true
            if (activityManager.memoryClass <= Device.LOW_END_HEAP_MB) return true
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            if (memory.totalMem in 1..Device.LOW_END_TOTAL_MEMORY_BYTES) return true
            return Runtime.getRuntime().availableProcessors() <= Device.LOW_END_CORES
        }
    }
}
