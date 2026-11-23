package com.sameerasw.airsync

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.sameerasw.airsync.data.local.RemovePlaybackPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemovePlaybackPreferencesTest {
    @Test fun upgradeDropsRetiredMetadataAndFlagsWithoutTouchingPairingOrNotificationAccess() = runBlocking {
        val key = stringPreferencesKey("last_connected_symmetric_key")
        val notification = booleanPreferencesKey("notification_sync_enabled")
        val old = mutablePreferencesOf(key to "synthetic-pairing", notification to true,
            stringPreferencesKey("mac_music_title") to "synthetic-track",
            stringPreferencesKey("mac_music_artist") to "synthetic-artist",
            booleanPreferencesKey("send_now_playing_enabled") to true)
        val migration = RemovePlaybackPreferences()
        assertTrue(migration.shouldMigrate(old))
        val updated = migration.migrate(old)
        assertFalse(updated.asMap().keys.any { RemovePlaybackPreferences.isRetiredKey(it.name) })
        assertEquals("synthetic-pairing", updated[key])
        assertEquals(true, updated[notification])
        assertFalse(migration.shouldMigrate(updated))
    }

    @Test fun obsoleteMusicDrivenTabBecomesConnectWhileExplicitRemoteTabIsKept() = runBlocking {
        val tab = stringPreferencesKey("default_tab")
        val migration = RemovePlaybackPreferences()
        assertEquals("connect", migration.migrate(mutablePreferencesOf(tab to "dynamic"))[tab])
        assertEquals("remote", migration.migrate(mutablePreferencesOf(tab to "remote"))[tab])
    }
}
