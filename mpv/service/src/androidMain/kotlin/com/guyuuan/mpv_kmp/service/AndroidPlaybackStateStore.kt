package com.guyuuan.mpv_kmp.service

import android.content.Context

/** Persists the playback queue in application-private SharedPreferences. */
class AndroidPlaybackStateStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val key: String = DEFAULT_STATE_KEY
) : PlaybackStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    override fun load(): RestorablePlaybackState? =
        preferences.getString(key, null)?.let(PlaybackStateCodec::decode)

    override fun save(state: RestorablePlaybackState) {
        preferences.edit().putString(key, PlaybackStateCodec.encode(state)).apply()
    }

    override fun clear() {
        preferences.edit().remove(key).apply()
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "mpv_kmp_playback"
        const val DEFAULT_STATE_KEY = "playback_state"
    }
}
