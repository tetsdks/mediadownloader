package com.markhoor.mediadownloader

/** How hard the module may work the device. */
enum class PerformanceMode {
    /** Low-end treatment on devices with little memory or few cores, full speed elsewhere. */
    Auto,

    /** Full parallelism and page scripts at their normal pace, whatever the device. */
    Standard,

    /** Fewer parallel requests, slower page scans, no button animations, whatever the device. */
    LowEnd,
}
