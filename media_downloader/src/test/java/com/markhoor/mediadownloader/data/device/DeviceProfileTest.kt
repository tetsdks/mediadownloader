package com.markhoor.mediadownloader.data.device

import com.markhoor.mediadownloader.core.Constants.Device
import com.markhoor.mediadownloader.core.Constants.Network
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    private val heapShare = Runtime.getRuntime().maxMemory() / Device.HEAP_SHARE_PER_RESPONSE

    @Test
    fun `a response never takes more than its share of the heap, nor more than the tier allows`() {
        val standard = DeviceProfile(isLowEnd = false).maxResponseBytes
        val lowEnd = DeviceProfile(isLowEnd = true).maxResponseBytes
        assertTrue(standard <= heapShare && standard <= Network.MAX_RESPONSE_BYTES)
        assertTrue(lowEnd <= heapShare && lowEnd <= Device.LOW_END_MAX_RESPONSE_BYTES)
        assertTrue(lowEnd <= standard)
    }
}
