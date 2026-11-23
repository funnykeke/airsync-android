package com.sameerasw.airsync.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import com.sameerasw.airsync.utils.WebSocketUtil

/** System output volume only; no player, metadata, artwork or playback controls. */
@Composable
fun SystemVolumeControls() {
    val volume by WebSocketMessageHandler.macSystemVolume.collectAsState()
    fun send(action: String) {
        WebSocketUtil.sendMessage("""{"type":"volumeControl","data":{"action":"$action"}}""")
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        Text(stringResource(R.string.system_volume), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = { send("decrease") }) {
            Icon(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.volume_down))
        }
        IconButton(onClick = { send("toggleMute") }) {
            Icon(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.toggle_mute))
        }
        IconButton(onClick = { send("increase") }) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.volume_up))
        }
        Text(volume?.let { "$it%" } ?: "—")
    }
}
