package com.guyuuan.mpv_kmp

import co.touchlab.kermit.Logger
import org.jetbrains.skia.Bitmap

internal class SkiaBitmapVideoSurface {
    private var isInit = false
    private var bitmap : Bitmap  = Bitmap()
    fun attch(mpv: SoftwareRenderContextSupport){
        if (isInit)  throw Error("SkiaBitmapVideoSurface attached,don't call this method more than once")
        Logger.d(tag = "MpvComposeView") { "initializing software render context" }
        if (mpv.createSoftwareRenderContext()) {
            Logger.d(tag = "MpvComposeView") { "software render context created" }
            mpv.setRenderCallback {

            }
            isInit = true
        } else {
            Logger.e(tag = "MpvComposeView") { "failed to create software render context" }
        }
    }
}
