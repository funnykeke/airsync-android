package com.sameerasw.airsync.utils

/** Only permanent local endpoints may replace the editable saved LAN address. */
object ConnectionEndpointPolicy {
    fun editableAddress(mode: String, activeAddress: String?): String? =
        activeAddress?.takeIf { mode == "local" && it.isNotBlank() }

    /** Repair only the known interim overwrite, using an existing numeric endpoint. */
    fun recoverSavedAddress(savedAddress: String, lastLocalAddress: String?): String {
        if (savedAddress != "127.0.0.1") return savedAddress
        val candidate = lastLocalAddress?.trim() ?: return savedAddress
        val isV4 = candidate.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
            candidate.split('.').mapNotNull { it.toIntOrNull() }.let { octets ->
                octets.size == 4 && octets.all { it in 0..255 } && octets.first() in 1..223 && octets.first() != 127
            }
        // A colon and numeric-only syntax prevent hostname/DNS lookups.
        val isV6 = ':' in candidate && candidate.matches(Regex("[0-9a-fA-F:.]+")) && runCatching {
            val address = java.net.InetAddress.getByName(candidate)
            !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isMulticastAddress
        }.getOrDefault(false)
        return if (isV4 || isV6) candidate else savedAddress
    }
}
