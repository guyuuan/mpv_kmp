package com.guyuuan.kmp.mpv.service

import android.content.Context
import androidx.core.content.edit

/** Persists the playback queue in application-private SharedPreferences. */
class AndroidPlaybackStateStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val key: String = DEFAULT_STATE_KEY
) : PlaybackStateStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )
    private val legacyPreferences = if (preferencesName == DEFAULT_PREFERENCES_NAME) {
        applicationContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    } else {
        null
    }

    override fun load(): RestorablePlaybackState? {
        preferences.getString(key, null)?.let { return PlaybackStateCodec.decode(it) }
        val legacyPreferences = legacyPreferences ?: return null
        val restoredState = legacyPreferences
            .getString(key, null)
            ?.let(PlaybackStateCodec::decode)
            ?: return null
        preferences.edit { putString(key, PlaybackStateCodec.encode(restoredState)) }
        legacyPreferences.edit { remove(key) }
        return restoredState
    }

    override fun save(state: RestorablePlaybackState) {
        preferences.edit { putString(key, PlaybackStateCodec.encode(state)) }
    }

    override fun clear() {
        preferences.edit { remove(key) }
        legacyPreferences?.edit { remove(key) }
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "com.guyuuan.kmp.mpv.playback"
        const val LEGACY_PREFERENCES_NAME = "mpv_kmp_playback"
        const val DEFAULT_STATE_KEY = "playback_state"
    }
}
