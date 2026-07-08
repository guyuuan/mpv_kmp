package com.guyuuan.mpv_kmp

import org.jetbrains.skia.Bitmap

internal class SkiaBitmapVideoSurface {
    private var isInit = false
    private var bitmap : Bitmap  = Bitmap()
    fun attch(mpv: JvmMpv){
        if (isInit)  throw Error("SkiaBitmapVideoSurface attached,don't call this method more than once")
        println("MpvComposeView: Initializing RenderContext (SW)...")
        if (mpv.createSoftwareRenderContext()) {
            println("MpvComposeView: RenderContext created successfully.")
            mpv.setRenderCallback {

            }
            isInit = true
        } else {
            println("MpvComposeView: Failed to create RenderContext.")
        }
    }
}