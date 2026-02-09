package com.guyuuan.mpv_kmp

import com.sun.jna.Native
import java.awt.Canvas
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

class MpvCanvas : Canvas() {
    private var player: MpvPlayer? = null

    init {
        addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent?) {
                super.componentShown(e)
                attachPlayer()
            }
            
            override fun componentResized(e: ComponentEvent?) {
                 // MPV usually handles resize if attached to window
            }
        })
    }

    fun setPlayer(player: MpvPlayer) {
        this.player = player
        if (isDisplayable) {
            attachPlayer()
        }
    }

    private fun attachPlayer() {
        val p = player ?: return
        // Get native window handle
        // Note: component must be displayable (added to a peer)
        try {
            val wid = Native.getComponentID(this)
            if (wid != 0L) {
                p.attach(wid)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
    
    fun detach() {
        player?.detach()
    }
}
