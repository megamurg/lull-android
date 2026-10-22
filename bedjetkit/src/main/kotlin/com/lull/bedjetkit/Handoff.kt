package com.lull.bedjetkit

import java.security.MessageDigest
import java.util.UUID

/**
 * The UUID a phone advertises to ask another phone to hand its BedJet over. Port of
 * `BLELink.handoffUUID(forDeviceNamed:)`; it must give the same UUID as the iOS app, so an iPhone and an
 * Android phone can hand one BedJet to each other.
 */
object Handoff {
    /**
     * Requests are specific to one BedJet, so a neighbour's phone looking for theirs is ignored.
     * The first 16 bytes of SHA-256("lull.handoff." + name), marked as a version 5, RFC 4122 variant UUID.
     */
    fun uuid(deviceName: String): UUID {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("lull.handoff.$deviceName".encodeToByteArray())
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        // Big-endian, as CoreBluetooth reads `CBUUID(data:)`.
        val most = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[i].toLong() and 0xff) }
        val least = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (bytes[i].toLong() and 0xff) }
        return UUID(most, least)
    }
}
