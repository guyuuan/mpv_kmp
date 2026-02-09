package com.guyuuan.mpv_kmp

data class MpvEventDTO(val eventId: Int, val error: Int, val propName: String?, val propValue: String?)

object MpvNative {
    init {
        System.loadLibrary("mpvbridge")
    }
    external fun mpvInit(): Boolean
    external fun mpvAttachSurface(surface: android.view.Surface)
    external fun mpvDetachSurface()
    external fun mpvCommandString(cmd: String): Int
    external fun mpvSetProperty(name: String, value: String): Int
    external fun mpvGetProperty(name: String): String?
    external fun mpvObserveProperty(name: String, format: Int): Int
    external fun mpvWaitEvent(timeout: Double): MpvEventDTO?
    external fun mpvWakeup()
    external fun mpvTerminate()
}
