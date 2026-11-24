package com.sameerasw.airsync.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class ClipboardImageProvider : FileProvider()

object ClipboardUtil {
    private const val TAG = "ClipboardUtil"

    /**
     * Get the current text from clipboard
     */
    fun getClipboardText(context: Context): String? {
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboardManager.primaryClip

            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                item.text?.toString() ?: if (clipData.description.hasMimeType("image/*")) {
                    null
                } else {
                    item.coerceToText(context)?.toString()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            // Keep error logging for exceptions
            Log.e(TAG, "Error getting clipboard text: ${e.message}")
            null
        }
    }

    /**
     * Set text to clipboard
     */
    fun setClipboardText(context: Context, text: String): Boolean {
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("AirSync", text)
            clipboardManager.setPrimaryClip(clipData)
            Log.d(TAG, "Text set to clipboard")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting clipboard text: ${e.message}")
            false
        }
    }

    /**
     * Register clipboard change listener
     * Returns a listener that can be used to unregister
     */
    fun registerClipboardListener(
        context: Context,
        onClipboardChanged: (String) -> Unit
    ): ClipboardManager.OnPrimaryClipChangedListener {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clipText = getClipboardText(context)
                if (!clipText.isNullOrEmpty()) {
                    Log.d(TAG, "Clipboard changed")
                    onClipboardChanged(clipText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in clipboard listener: ${e.message}")
            }
        }

        clipboardManager.addPrimaryClipChangedListener(listener)
        Log.d(TAG, "Clipboard listener registered")
        return listener
    }

    /**
     * Unregister clipboard change listener
     */
    fun unregisterClipboardListener(
        context: Context,
        listener: ClipboardManager.OnPrimaryClipChangedListener
    ) {
        try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.removePrimaryClipChangedListener(listener)
            Log.d(TAG, "Clipboard listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering clipboard listener: ${e.message}")
        }
    }

    @Synchronized
    fun copyImageToClipboard(context: Context, source: File, ownsSession: () -> Boolean): Boolean {
        val directory = File(context.cacheDir, "clipboard-images")
        var cached: File? = null
        var published = false
        try {
            if (!ownsSession()) return false
            directory.mkdirs()
            val image = File.createTempFile("image-", ".png", directory)
            cached = image
            source.copyTo(image, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.clipboard-images", image)
            val clip = ClipData.newUri(context.contentResolver, "AirSync Image", uri)
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            if (!ownsSession()) return false
            clipboard.setPrimaryClip(clip)
            published = true
            directory.listFiles()?.filter { it != image }?.sortedByDescending { it.lastModified() }
                ?.drop(3)?.forEach { it.delete() }
        } catch (_: Exception) {
            Log.w(TAG, "Could not publish image clipboard")
        } finally {
            if (!published) cached?.delete()
        }
        return published
    }
}
