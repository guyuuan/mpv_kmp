@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.guyuuan.kmp.mpv.bridge

import kotlinx.cinterop.COpaquePointer
import kotlin.native.CName

@CName("JNI_OnLoad")
fun jniOnLoad(vm: COpaquePointer?, reserved: COpaquePointer?): Int =
    mpv_bridge_on_load(vm, reserved)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvInit")
fun mpvInit(env: COpaquePointer?, clazz: COpaquePointer?): UByte =
    mpv_bridge_init(env, clazz)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvCreate")
fun mpvCreate(env: COpaquePointer?, clazz: COpaquePointer?): UByte =
    mpv_bridge_create(env, clazz)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvSetOption")
fun mpvSetOption(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    name: COpaquePointer?,
    value: COpaquePointer?,
): Int = mpv_bridge_set_option(env, clazz, name, value)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvInitialize")
fun mpvInitialize(env: COpaquePointer?, clazz: COpaquePointer?): UByte =
    mpv_bridge_initialize(env, clazz)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvAttachSurface")
fun mpvAttachSurface(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    surface: COpaquePointer?,
) = mpv_bridge_attach_surface(env, clazz, surface)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvDetachSurface")
fun mpvDetachSurface(env: COpaquePointer?, clazz: COpaquePointer?) =
    mpv_bridge_detach_surface(env, clazz)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvCommandString")
fun mpvCommandString(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    command: COpaquePointer?,
): Int = mpv_bridge_command_string(env, clazz, command)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvSetProperty")
fun mpvSetProperty(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    name: COpaquePointer?,
    value: COpaquePointer?,
): Int = mpv_bridge_set_property(env, clazz, name, value)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvGetProperty")
fun mpvGetProperty(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    name: COpaquePointer?,
): COpaquePointer? = mpv_bridge_get_property(env, clazz, name)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvObserveProperty")
fun mpvObserveProperty(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    name: COpaquePointer?,
    replyUserdata: Long,
    format: Int,
): Int = mpv_bridge_observe_property(env, clazz, name, replyUserdata, format)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvUnobserveProperty")
fun mpvUnobserveProperty(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    replyUserdata: Long,
): Int = mpv_bridge_unobserve_property(env, clazz, replyUserdata)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvWaitEvent")
fun mpvWaitEvent(
    env: COpaquePointer?,
    clazz: COpaquePointer?,
    timeout: Double,
): COpaquePointer? = mpv_bridge_wait_event(env, clazz, timeout)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvWakeup")
fun mpvWakeup(env: COpaquePointer?, clazz: COpaquePointer?) =
    mpv_bridge_wakeup(env, clazz)

@CName("Java_com_guyuuan_kmp_mpv_MpvNative_mpvTerminate")
fun mpvTerminate(env: COpaquePointer?, clazz: COpaquePointer?) =
    mpv_bridge_terminate(env, clazz)
