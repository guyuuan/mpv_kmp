package com.guyuuan.mpv_kmp.service

import platform.Foundation.NSUserDefaults

/** Persists the playback queue in the host application's NSUserDefaults domain. */
class IosPlaybackStateStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val key: String = DEFAULT_STATE_KEY
) : PlaybackStateStore {
    override fun load(): RestorablePlaybackState? =
        defaults.stringForKey(key)?.let(PlaybackStateCodec::decode)

    override fun save(state: RestorablePlaybackState) {
        defaults.setObject(PlaybackStateCodec.encode(state), forKey = key)
    }

    override fun clear() {
        defaults.removeObjectForKey(key)
    }

    private companion object {
        const val DEFAULT_STATE_KEY = "com.guyuuan.mpv-kmp.playback-state"
    }
}
