package com.sameerasw.airsync.utils

import com.sameerasw.airsync.domain.model.SystemVolume
import com.sameerasw.airsync.domain.model.BatteryInfo

object JsonUtil {
    /** Status contains battery, pairing and independent system volume only. */
    fun createDeviceStatusJson(battery: BatteryInfo, volume: SystemVolume): String =
        """{"type":"status","data":{"battery":{"level":${battery.level},"isCharging":${battery.isCharging}},"isPaired":true,"volume":{"level":${volume.level.coerceIn(0, 100)},"isMuted":${volume.isMuted}}}}"""


    /**
     * Ensures JSON string is a single line by removing all newlines and extra whitespace
     */
    fun toSingleLine(json: String): String {
        return json.replace(Regex("\\s*\\n\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Creates a single-line JSON string for device info
     */
    fun createDeviceInfoJson(
        name: String,
        ipAddress: String,
        port: Int,
        version: String,
        targetIpAddress: String? = null
    ): String {
        val targetIpJson =
            if (targetIpAddress != null) """, "targetIpAddress": "$targetIpAddress" """ else ""
        return """{"type":"device","data":{"name":"${escape(name)}","ipAddress":"$ipAddress","port":$port,"version":"${escape(version)}","adbPorts":[]$targetIpJson}}"""
    }

    /**
     * Creates a single-line JSON string for device info with ADB ports
     */
    fun createDeviceInfoJson(
        id: String,
        name: String,
        ipAddress: String,
        port: Int,
        version: String,
        adbPorts: List<String>,
        targetIpAddress: String? = null
    ): String {
        val portsJson = adbPorts.joinToString(",") { "\"$it\"" }
        val targetIpJson =
            if (targetIpAddress != null) """, "targetIpAddress": "$targetIpAddress" """ else ""
        return """{"type":"device","data":{"id":"$id","name":"${escape(name)}","ipAddress":"$ipAddress","port":$port,"version":"${escape(version)}","adbPorts":[$portsJson]$targetIpJson}}"""
    }

    /**
     * Creates a single-line JSON string for device info with wallpaper
     */
    fun createDeviceInfoJson(
        id: String,
        name: String,
        ipAddress: String,
        port: Int,
        version: String,
        wallpaperBase64: String?,
        adbPorts: List<String>,
        targetIpAddress: String? = null
    ): String {
        val wallpaperJson = if (wallpaperBase64 != null) {
            ""","wallpaper":"$wallpaperBase64""""
        } else {
            ""
        }
        val portsJson = adbPorts.joinToString(",") { "\"$it\"" }
        val targetIpJson =
            if (targetIpAddress != null) """, "targetIpAddress": "$targetIpAddress" """ else ""
        return """{"type":"device","data":{"id":"$id","name":"${escape(name)}","ipAddress":"$ipAddress","port":$port,"version":"${escape(version)}","adbPorts":[$portsJson]$wallpaperJson$targetIpJson}}"""
    }

    /**
     * Creates a single-line JSON string for notifications with unique ID
     */
    fun createNotificationJson(
        id: String,
        title: String,
        body: String,
        app: String,
        packageName: String
    ): String {
        return """{"type":"notification","data":{"id":"${escape(id)}","title":"${escape(title)}","body":"${escape(body)}","app":"${escape(app)}","package":"${escape(packageName)}"}}"""
    }

    /**
     * Creates a single-line JSON string for notifications with actions included.
     * actions: list of objects { name: String, type: "button" | "reply" }
     */
    fun createNotificationJson(
        id: String,
        title: String,
        body: String,
        app: String,
        packageName: String,
        priority: String = "alerting",
        actions: List<Pair<String, String>>,
        progress: Int? = null,
        progressMax: Int? = null,
        progressIndeterminate: Boolean? = null,
        ongoing: Boolean? = null
    ): String {
        val actionsJson = if (actions.isNotEmpty()) {
            val items = actions.joinToString(",") { (name, type) ->
                """{"name":"${escape(name)}","type":"${escape(type)}"}"""
            }
            ",\"actions\":[${items}]"
        } else {
            ""
        }
        val progressJson = if (progress != null) ",\"progress\":$progress" else ""
        val progressMaxJson = if (progressMax != null) ",\"progressMax\":$progressMax" else ""
        val progressIndeterminateJson = if (progressIndeterminate != null) ",\"progressIndeterminate\":$progressIndeterminate" else ""
        val ongoingJson = if (ongoing != null) ",\"ongoing\":$ongoing" else ""
        return """{"type":"notification","data":{"id":"$id","title":"${escape(title)}","body":"${
            escape(
                body
            )
        }","app":"${escape(app)}","package":"${escape(packageName)}","priority":"$priority"$actionsJson$progressJson$progressMaxJson$progressIndeterminateJson$ongoingJson}}"""
    }

    /**
     * Creates a one-line JSON for Android->Mac notification state updates (e.g., dismissals).
     * If action is not provided, defaults to "dismiss" when dismissed=true.
     */
    fun createNotificationUpdateJson(
        id: String,
        dismissed: Boolean = true,
        action: String? = null
    ): String {
        val safeAction = action ?: if (dismissed) "dismiss" else null
        val actionPart = safeAction?.let { ",\"action\":\"${escape(it)}\"" } ?: ""
        return """{"type":"notificationUpdate","data":{"id":"$id","dismissed":$dismissed$actionPart}}"""
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // Other control characters are not allowed unescaped in JSON strings
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }


    /**
     * Creates a response JSON for notification dismissal result
     */
    fun createNotificationDismissalResponse(
        id: String,
        success: Boolean,
        message: String = ""
    ): String {
        return """{"type":"dismissalResponse","data":{"id":"$id","success":$success,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a response JSON for notification action result
     */
    fun createNotificationActionResponse(
        id: String,
        actionName: String,
        success: Boolean,
        message: String = ""
    ): String {
        return """{"type":"notificationActionResponse","data":{"id":"$id","action":"${
            escape(
                actionName
            )
        }","success":$success,"message":"${escape(message)}"}}"""
    }


    /**
     * Creates a response JSON for volume control result
     */
    fun createVolumeControlResponse(
        action: String,
        success: Boolean,
        message: String = ""
    ): String {
        return """{"type":"volumeControlResponse","data":{"action":"$action","success":$success,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a JSON string for app icons
     */
    fun createAppIconsJson(
        apps: List<com.sameerasw.airsync.domain.model.NotificationApp>,
        iconMap: Map<String, String>
    ): String {
        val appEntries = apps.joinToString(",") { app ->
            val iconData = iconMap[app.packageName] ?: ""
            """
            "${app.packageName}": {
                "name": "${escape(app.appName)}",
                "icon": "$iconData",
                "listening": ${app.isEnabled},
                "systemApp": ${app.isSystemApp}
            }
            """.trimIndent()
        }
        return """{"type":"appIcons","data":{ $appEntries }}"""
    }

    /**
     * Creates a JSON string for clipboard updates
     */
    fun createClipboardUpdateJson(text: String): String {
        // Escape quotes and newlines in the text
        val escapedText = escape(text)
        return """{"type":"clipboardUpdate","data":{"text":"$escapedText"}}"""
    }

    /**
     * Creates a response JSON for app notification toggle result
     */
    fun createToggleAppNotificationResponse(
        packageName: String,
        success: Boolean,
        newState: Boolean,
        message: String = ""
    ): String {
        return """{"type":"toggleAppNotifResponse","data":{"package":"$packageName","success":$success,"newState":$newState,"message":"${escape(message)}"}}"""
    }


}
