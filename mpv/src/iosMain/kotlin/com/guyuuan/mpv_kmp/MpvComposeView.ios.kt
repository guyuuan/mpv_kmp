package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView
import platform.UIKit.UIColor
import platform.QuartzCore.CALayer
import kotlinx.cinterop.useContents
import kotlinx.cinterop.UnsafeMutableRawPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreGraphics.CGRectMake

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayerState
) {
    UIKitView(
        modifier = modifier,
        factory = {
            val view = UIView()
            view.backgroundColor = UIColor.blackColor
            
            // We need to attach after the layer is ready.
            // But UIKitView factory is called synchronously.
            // We can try to attach immediately or use a coordinator/delegate if available.
            // For simplicity, we can try attaching now, but the layer might not be backed by window yet.
            // However, MPV just needs the layer pointer usually.
            
            val layer = view.layer
            // Get pointer to layer
            // In Kotlin Native, we need to get the pointer value.
            // view.layer.objcPtr() ? No.
            // Let's rely on standard way to get pointer address.
            // Assuming we have an extension or bridge.
            // But wait, in MpvPlayer.ios.kt we handled CPointer.
            // Here we have ObjC object.
            // We need the raw pointer address of the CALayer.
            
            // Correct way to get pointer from ObjC object in K/N:
            // val ptr = kotlinx.cinterop.objcPtr(layer) -- not directly available in common API
            // Actually, we can use:
            // val ptr = kotlin.native.internal.ref.createRetainedExternalRCRef(layer) -- dangerous
            
            // Let's use standard interop.
            // Bridge to void* is automatic for CPointer? No.
            // view.layer is CALayer.
            
            // Workaround: Use CFBridgingRetain to get a void* (CPointer<out CPointed>)
            // Or simple casting if possible.
            
            // Actually, MpvPlayer.attach expects Any.
            // In iOS impl, we check for CPointer or Long.
            // We need the address of the CALayer object as Long.
            
            val ptr = kotlinx.cinterop.objc.objc_retainAutorelease(layer)
            // wait, objc_retainAutorelease returns valid object.
            
            // Let's try to get the address via formatting?
            // Or use a helper function in a .def or standard lib.
            
            // Another approach: Use the same logic as Swift's Unmanaged.passUnretained(layer).toOpaque()
            // In Kotlin Native:
            // val ptr = Bridge.getLayerPointer(layer)
            
            // Let's assume we can get it via standard ObjC interop.
            // The object reference itself IS the pointer.
            // We can cast it to CPointer?
            // val ptr: CPointer<*> = layer.objcPtr() // fake API
            
            // Real way:
            // val ptr = (layer as kotlinx.cinterop.CValuesRef<*>).getPointer(memScope) -- no
            
            // Fallback: Pass the layer object itself if we can cast it to CPointer in implementation?
            // No, ObjC objects are references, not CPointer directly in type system.
            
            // Let's use implicit cast to id, then to void*?
            // Kotlin Native treats ObjC classes as types.
            
            // Let's use `objc_lookUpClass` style or similar?
            
            // Simpler: Just pass the view/layer to attach, and let attach handle it?
            // But attach takes Any. MpvPlayer.ios.kt checks for CPointer or Long.
            // It doesn't check for UIView/CALayer yet.
            
            // Let's update MpvPlayer.ios.kt to accept CALayer/UIView if possible?
            // Or better, convert here.
            
            // To get pointer from ObjC object `obj`:
            // val ptr = UnsafeMutableRawPointer(obj) -- constructor might exist?
            // No.
            
            // use: val ptr = Bridge.bridge(layer)
            // We don't have a bridge.
            
            // Let's use `objc_id`?
            
            // We can use:
            // val ptr = (layer as? kotlinx.cinterop.CPointed)?.ptr -- No.
            
            // Let's use a dirty trick or standard `ObjCInterop.getPointer`?
            // Actually, `objc_retain` returns a pointer?
            
            // In Swift: Unmanaged.passUnretained(layer).toOpaque()
            // In Kotlin:
            // val ptr = interpretCPointer<CPointed>(layer.objcPtr())
            
            // Let's try to pass the Long address.
            // val addr: Long = layer.hashCode().toLong() // Unreliable? usually address.
            // No, hashCode is not address.
            
            // Okay, let's look at `MpvPlayer.ios.kt`. It accepts CPointer.
            // How to convert CALayer to CPointer<*>?
            // val ptr = layer.reinterpret<...>()?
            
            // Found a common pattern:
            // use `(__bridge void*)layer` in C.
            
            // In Kotlin Native, we can define a C function in a .def file, or use an existing one.
            // But we can't easily add C code here.
            
            // Wait, we can use `CoreFoundation`.
            // CFBridgingRetain(layer) returns CPointer.
            // platform.Foundation.CFBridgingRetain?
            // It's in CoreFoundation.
            
            val ptr = platform.Foundation.CFBridgingRetain(layer)
            if (ptr != null) {
                // We have a CPointer.
                // We can pass it directly.
                // But we should balance it with Release if we retained it.
                // Or use CFBridgingRelease immediately?
                // Or passUnretained equivalent?
                // platform.CoreFoundation.CFTypeRef?
                
                // attach expects CPointer or Long.
                state.player.attach(ptr)
                
                // Since we retained, we should release if we don't want to hold it extra.
                // But attach might need it valid.
                // View holds layer.
                platform.Foundation.CFBridgingRelease(ptr) 
                // Wait, if we release, is the pointer still valid value-wise? Yes.
                // We just used it to get the address.
            }
            
            view
        },
        update = { view ->
             // update logic
        }
    )
}
