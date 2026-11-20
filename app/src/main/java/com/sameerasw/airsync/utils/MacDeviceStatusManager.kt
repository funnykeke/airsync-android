package com.sameerasw.airsync.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.MacBattery
import com.sameerasw.airsync.domain.model.MacDeviceStatus
import com.sameerasw.airsync.domain.model.MacMusicInfo
import com.sameerasw.airsync.service.MacMediaPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object MacDeviceStatusManager {
    private const val TAG = "MacDeviceStatusManager"

    private val _macDeviceStatus = MutableStateFlow<MacDeviceStatus?>(null)
    val macDeviceStatus: StateFlow<MacDeviceStatus?> = _macDeviceStatus.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    fun updateBatteryStatus(context: Context, level: Int, isCharging: Boolean) {
        val current = _macDeviceStatus.value
        updateStatus(
            context = context,
            name = current?.name ?: "Unknown",
            batteryLevel = level,
            isCharging = isCharging,
            isPaired = current?.isPaired ?: true,
            isPlaying = current?.music?.isPlaying ?: false,
            title = current?.music?.title ?: "",
            artist = current?.music?.artist ?: "",
            volume = current?.music?.volume ?: 0,
            isMuted = current?.music?.isMuted ?: false,
            albumArt = null, // keep current
            likeStatus = current?.music?.likeStatus ?: "none",
            elapsedTime = current?.music?.elapsedTime ?: 0L,
            duration = current?.music?.duration ?: 0L,
            timestamp = current?.music?.timestamp,
            playbackRate = current?.music?.playbackRate ?: 1.0
        )
    }

    fun updateMacStatus(context: Context, name: String) {
        val current = _macDeviceStatus.value
        updateStatus(
            context = context,
            name = name,
            batteryLevel = current?.battery?.level ?: -1,
            isCharging = current?.battery?.isCharging ?: false,
            isPaired = current?.isPaired ?: true,
            isPlaying = current?.music?.isPlaying ?: false,
            title = current?.music?.title ?: "",
            artist = current?.music?.artist ?: "",
            volume = current?.music?.volume ?: 0,
            isMuted = current?.music?.isMuted ?: false,
            albumArt = null, // keep current
            likeStatus = current?.music?.likeStatus ?: "none",
            elapsedTime = current?.music?.elapsedTime ?: 0L,
            duration = current?.music?.duration ?: 0L,
            timestamp = current?.music?.timestamp,
            playbackRate = current?.music?.playbackRate ?: 1.0
        )
    }

    fun updateMusicStatus(
        context: Context,
        isPlaying: Boolean,
        title: String,
        artist: String,
        volume: Int,
        isMuted: Boolean,
        likeStatus: String,
        albumArt: String? = null
    ) {
        val current = _macDeviceStatus.value
        updateStatus(
            context = context,
            name = current?.name ?: "Unknown",
            batteryLevel = current?.battery?.level ?: -1,
            isCharging = current?.battery?.isCharging ?: false,
            isPaired = current?.isPaired ?: true,
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            volume = volume,
            isMuted = isMuted,
            albumArt = albumArt,
            likeStatus = likeStatus,
            elapsedTime = current?.music?.elapsedTime ?: 0L,
            duration = current?.music?.duration ?: 0L,
            timestamp = current?.music?.timestamp,
            playbackRate = current?.music?.playbackRate ?: 1.0
        )
    }

    fun updateStatus(
        context: Context,
        name: String,
        batteryLevel: Int,
        isCharging: Boolean,
        isPaired: Boolean,
        isPlaying: Boolean,
        title: String,
        artist: String,
        volume: Int,
        isMuted: Boolean,
        albumArt: String?,
        likeStatus: String,
        elapsedTime: Long = 0L,
        duration: Long = 0L,
        timestamp: String? = null,
        playbackRate: Double = 1.0
    ) {
        try {
            val effectiveAlbumArt = albumArt ?: _macDeviceStatus.value?.music?.albumArt ?: ""

            val macBattery = MacBattery(level = batteryLevel, isCharging = isCharging)
            val macMusicInfo = MacMusicInfo(
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                volume = volume,
                isMuted = isMuted,
                albumArt = effectiveAlbumArt,
                likeStatus = likeStatus,
                elapsedTime = elapsedTime,
                duration = duration,
                timestamp = timestamp,
                playbackRate = playbackRate
            )

            val status = MacDeviceStatus(
                name = name,
                battery = macBattery,
                isPaired = isPaired,
                music = macMusicInfo
            )

            _macDeviceStatus.value = status

            var bitmap: Bitmap? = _albumArt.value

            if (albumArt != null) {
                bitmap = decodeAlbumArt(albumArt)
                _albumArt.value = bitmap
            }

            // Start/update or stop the Mac media player service based on media state and USER SETTING
            CoroutineScope(Dispatchers.IO).launch {
                val ds = DataStoreManager.getInstance(context)
                val isMediaControlsEnabled = ds.getMacMediaControlsEnabled().first()
                val isConnected =
                    WebSocketUtil.isConnected() || WebSocketUtil.isConnecting()

                if (isConnected && isMediaControlsEnabled && (title.isNotEmpty() || artist.isNotEmpty() || isPlaying)) {
                    MacMediaPlayerService.startMacMedia(
                        context,
                        title,
                        artist,
                        isPlaying,
                        bitmap,
                        elapsedTime,
                        duration,
                        timestamp,
                        playbackRate
                    )
                    Log.d(TAG, "Started/Updated Mac media player service")
                } else {
                    MacMediaPlayerService.stopMacMedia(context)
                    if (!isMediaControlsEnabled) {
                        Log.d(
                            TAG,
                            "Stopped Mac media player service - controls explicitly disabled by user"
                        )
                    } else if (!isConnected) {
                        Log.d(TAG, "Stopped Mac media player service - device disconnected")
                    } else {
                        Log.d(TAG, "Stopped Mac media player service - no active media")
                    }
                }
            }

            Log.d(
                TAG,
                "Mac device status updated - Playing: $isPlaying, Title: $title, Artist: $artist"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Mac device status: ${e.message}")
        }
    }

    private fun decodeAlbumArt(base64String: String): Bitmap? {
        return try {
            if (base64String.isNotEmpty()) {
                val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, boundsOptions)

                val targetSize = 512
                var inSampleSize = 1
                if (boundsOptions.outHeight > targetSize || boundsOptions.outWidth > targetSize) {
                    val halfHeight = boundsOptions.outHeight / 2
                    val halfWidth = boundsOptions.outWidth / 2
                    while ((halfHeight / inSampleSize) >= targetSize && (halfWidth / inSampleSize) >= targetSize) {
                        inSampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, decodeOptions)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding album art: ${e.message}")
            null
        }
    }

    fun startMonitoring(context: Context) {
        // No-op: external third-party bridge removed
    }

    fun stopMonitoring() {
        // No-op
    }

    fun cleanup(context: Context? = null) {
        try {
            _albumArt.value = null
            _macDeviceStatus.value = null
            Log.d(TAG, "Mac device status cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
}
