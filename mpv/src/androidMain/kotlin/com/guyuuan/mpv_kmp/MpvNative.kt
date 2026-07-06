package com.guyuuan.mpv_kmp

data class MpvEventDTO(
    val eventId: Int,
    val error: Int,
    val replyUserdata: Long,
    val propName: String?,
    val propValue: String?
)

object MpvNative {
    init {
        System.loadLibrary("mpvbridge")
    }
    external fun mpvInit(): Boolean
    external fun mpvCreate(): Boolean
    external fun mpvSetOption(name: String, value: String): Int
    external fun mpvInitialize(): Boolean
    external fun mpvAttachSurface(surface: android.view.Surface)
    external fun mpvDetachSurface()
    external fun mpvCommandString(cmd: String): Int
    external fun mpvSetProperty(name: String, value: String): Int
    external fun mpvGetProperty(name: String): String?
    external fun mpvObserveProperty(name: String, replyUserdata: Long, format: Int): Int
    external fun mpvUnobserveProperty(replyUserdata: Long): Int
    external fun mpvWaitEvent(timeout: Double): MpvEventDTO?
    external fun mpvWakeup()
    external fun mpvTerminate()
}
