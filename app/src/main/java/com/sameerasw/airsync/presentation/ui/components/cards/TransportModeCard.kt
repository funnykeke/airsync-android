package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.ConnectionPhase
import com.sameerasw.airsync.utils.WebSocketUtil

/**
 * 连接方式设置卡片：局域网直连 / 服务器中转。
 */
@Composable
fun TransportModeCard(
    transportMode: String,              // "local" | "relay"
    isConnected: Boolean,
    isConnecting: Boolean,
    relayServerUrl: String,
    relayRoom: String,
    relaySecret: String,
    relayPinnedCertSha256: String,
    encryptionKey: String?,
    errorMessage: String?,
    onModeChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onRoomChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onPinnedCertSha256Change: (String) -> Unit,
    onEncryptionKeyChange: (String) -> Unit,
    onSaveAndConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showSecret by remember { mutableStateOf(false) }
    val connection by WebSocketUtil.status.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_sync_desktop_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ui_connection_mode),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = transportMode == "local",
                    onClick = { onModeChange("local") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.ui_transport_local))
                }
                SegmentedButton(
                    selected = transportMode == "relay",
                    onClick = { onModeChange("relay") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.ui_transport_relay))
                }
            }

            Text(
                text = if (transportMode == "local") {
                    stringResource(R.string.ui_transport_local_desc)
                } else {
                    stringResource(R.string.ui_transport_relay_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (transportMode == "relay") {
                OutlinedTextField(
                    value = relayServerUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text(stringResource(R.string.ui_relay_server_url)) },
                    placeholder = { Text("wss://relay.example.com/relay") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = relayRoom,
                    onValueChange = onRoomChange,
                    label = { Text(stringResource(R.string.ui_relay_room)) },
                    placeholder = { Text("home") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = relaySecret,
                    onValueChange = onSecretChange,
                    label = { Text(stringResource(R.string.ui_relay_secret)) },
                    placeholder = { Text(stringResource(R.string.ui_relay_secret_hint)) },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showSecret = !showSecret }) {
                            Text(
                                if (showSecret) stringResource(R.string.ui_hide)
                                else stringResource(R.string.ui_show)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.ui_relay_secret_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = encryptionKey ?: "",
                    visualTransformation = PasswordVisualTransformation(),
                    onValueChange = onEncryptionKeyChange,
                    label = { Text(stringResource(R.string.ui_encryption_key_hint)) },
                    placeholder = { Text(stringResource(R.string.ui_encryption_key_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.ui_encryption_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = relayPinnedCertSha256,
                    onValueChange = onPinnedCertSha256Change,
                    label = { Text(stringResource(R.string.ui_relay_cert_pin)) },
                    placeholder = { Text(stringResource(R.string.ui_relay_cert_pin_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.ui_relay_cert_pin_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSaveAndConnect,
                        enabled = !isConnecting
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.ui_save_and_connect))
                    }

                    if (isConnected || isConnecting) {
                        TextButton(onClick = onDisconnect) {
                            Text(stringResource(R.string.ui_disconnect))
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(
                            id = if (isConnected) R.drawable.rounded_check_24
                            else R.drawable.rounded_sync_desktop_24
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) stringResource(R.string.ui_relay_connected)
                        else when (connection.phase) {
                            ConnectionPhase.WAITING_FOR_PEER -> stringResource(R.string.ui_relay_waiting)
                            ConnectionPhase.CONNECTING -> stringResource(R.string.notif_relay_connecting)
                            ConnectionPhase.HANDSHAKING -> stringResource(R.string.notif_relay_handshaking)
                            ConnectionPhase.RECONNECTING -> stringResource(R.string.notif_relay_reconnecting)
                            ConnectionPhase.ERROR -> connection.error ?: stringResource(R.string.relay_err_check_config)
                            else -> stringResource(R.string.relay_status_idle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = stringResource(R.string.ui_relay_limitation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
