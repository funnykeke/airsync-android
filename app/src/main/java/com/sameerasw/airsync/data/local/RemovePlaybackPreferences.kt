package com.sameerasw.airsync.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** Remove obsolete player settings during the first read after an upgrade. */
class RemovePlaybackPreferences : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().keys.any { isRetiredKey(it.name) } || currentData[defaultTab] == "dynamic"

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        asMap().keys.filter { isRetiredKey(it.name) }.forEach { remove(it) }
        if (this[defaultTab] == "dynamic") this[defaultTab] = "connect"
    }
    override suspend fun cleanUp() = Unit

    companion object {
        private val defaultTab = stringPreferencesKey("default_tab")
        private val retiredKeys = setOf("mac_music_title", "mac_music_artist", "send_now_playing_enabled",
            "excluded_media_packages", "mac_media_controls_enabled")
        fun isRetiredKey(name: String) = name in retiredKeys
    }
}
