package com.guyuuan.mpv_kmp

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var player: MpvPlayer? = null

    init {
        holder.addCallback(this)
    }

    fun setPlayer(player: MpvPlayer) {
        this.player = player
        if (holder.surface.isValid) {
            player.attach(holder.surface)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        player?.attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handle resize if necessary, MPV usually handles it via surface
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.detach()
    }
}
