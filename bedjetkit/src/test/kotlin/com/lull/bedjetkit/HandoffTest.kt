package com.lull.bedjetkit

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The vectors in PORT_PLAN §5.4, which the iOS app's `BLELink.handoffUUID(forDeviceNamed:)` produces. */
class HandoffTest {
    @Test
    fun matchesTheIosVectors() {
        assertEquals(UUID.fromString("80b8472d-c1d5-500b-868e-a410afbdebf4"), Handoff.uuid("BEDJET-3B04"))
        assertEquals(UUID.fromString("c0e13b2a-74b2-5a79-95e0-4fd6c06e26d2"), Handoff.uuid("BEDJET-3B05"))
        assertEquals(UUID.fromString("87206fc5-8f30-523e-bb4e-c5fb70d0c25d"), Handoff.uuid("Bedroom"))
    }

    @Test
    fun isAVersion5Rfc4122Uuid() {
        val uuid = Handoff.uuid("BEDJET-3B04")
        assertEquals(5, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun isSpecificToOneBedJet() {
        assertEquals(Handoff.uuid("BEDJET-3B04"), Handoff.uuid("BEDJET-3B04"))
        assertNotEquals(Handoff.uuid("BEDJET-3B04"), Handoff.uuid("BEDJET-3B05"))
    }
}
