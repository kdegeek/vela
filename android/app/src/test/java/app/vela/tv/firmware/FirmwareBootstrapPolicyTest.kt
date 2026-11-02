package app.vela.tv.firmware

import org.junit.Assert.*
import org.junit.Test

class FirmwareBootstrapPolicyTest {
    private val hands = FirmwareSettings.HANDS_COMPONENT
    private val ready = FirmwareEnvironment(
        firmwareBuild = true, supportedPlatform = true, systemApp = true, ownerUser = true,
        userUnlocked = true, deviceLocked = false, writeSecureSettingsGranted = true,
        mediaContentControlGranted = true, deviceProvisioned = "1", userSetupComplete = "1",
        tvUserSetupComplete = "1", ownerOptIn = "1",
    )

    private inner class Store : FirmwareBootstrapStore {
        var env = ready
        var marker: String? = null
        var services: String? = null
        var claimSucceeds = true
        var writeSucceeds = true
        var afterClaim: () -> Unit = {}
        val writes = mutableListOf<String>()
        override fun environment() = env
        override fun attempted() = marker
        override fun markAttempted(): Boolean {
            writes += "attempt"
            if (claimSucceeds) marker = "1"
            afterClaim()
            return claimSucceeds
        }
        override fun enabledServices() = services
        override fun putEnabledServices(value: String): Boolean {
            // The irrevocable claim must precede every side effect on platform accessibility.
            assertEquals("1", marker)
            writes += "services"
            if (writeSucceeds) services = value
            return writeSucceeds
        }
    }

    @Test fun `fresh settings enroll once after recording the attempt`() {
        val store = Store()
        assertEquals(FirmwareBootstrapResult.ENABLED, enrollFirmwareHands(store))
        assertEquals(hands, store.services)
        assertEquals(listOf("attempt", "services"), store.writes)
        repeat(3) { assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(store)) }
        assertEquals(listOf("attempt", "services"), store.writes)
    }

    @Test fun `merge preserves unrelated tokens duplicates order and empty tokens exactly`() {
        val before = ":other.app/.Reader::other.app/other.app.Reader:other.app/.Reader:"
        assertEquals("$before:$hands", appendHandsService(before))
        assertEquals(hands, appendHandsService(null))
        assertEquals(hands, appendHandsService(""))
    }

    @Test fun `short and full component forms are both idempotent without canonicalizing others`() {
        for (own in listOf(hands, "app.vela.tv/.control.HandsService")) {
            val before = "other.app/.Reader:$own::other.app/.Other"
            assertEquals(before, appendHandsService(before))
            assertEquals(before, appendHandsService(appendHandsService(before)))
        }
    }

    @Test fun `substring or similarly named service does not count as enrollment`() {
        val before = "$hands.Other:other.app/app.vela.tv.control.HandsService"
        assertEquals("$before:$hands", appendHandsService(before))
    }

    @Test fun `existing own grant is recorded without touching the enabled list`() {
        val store = Store().apply { services = "other.app/.Reader:app.vela.tv/.control.HandsService" }
        val before = store.services
        assertEquals(FirmwareBootstrapResult.ALREADY_ENABLED, enrollFirmwareHands(store))
        assertEquals(before, store.services)
        assertEquals(listOf("attempt"), store.writes)
        store.services = "other.app/.Reader" // Later Settings revocation is respected.
        assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(store))
        assertEquals("other.app/.Reader", store.services)
    }

    @Test fun `revocation after enrollment survives repeated boots app restarts and consent toggles`() {
        val first = Store()
        enrollFirmwareHands(first)
        val restarted = Store().apply {
            marker = first.marker // Secure settings survive process death and app-data clearing.
            services = "other.app/.Reader"
        }
        repeat(3) {
            restarted.env = ready.copy(ownerOptIn = "0")
            assertEquals(FirmwareBootstrapResult.WAITING, enrollFirmwareHands(restarted))
            restarted.env = ready
            assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(restarted))
        }
        assertEquals("other.app/.Reader", restarted.services)
        assertTrue(restarted.writes.isEmpty())
    }

    @Test fun `each missing security prerequisite blocks all writes`() {
        val blocked = listOf(
            ready.copy(firmwareBuild = false), ready.copy(supportedPlatform = false),
            ready.copy(systemApp = false), ready.copy(ownerUser = false),
            ready.copy(userUnlocked = false), ready.copy(deviceLocked = true),
            ready.copy(writeSecureSettingsGranted = false), ready.copy(mediaContentControlGranted = false),
            ready.copy(deviceProvisioned = null), ready.copy(deviceProvisioned = "0"),
            ready.copy(userSetupComplete = null), ready.copy(userSetupComplete = "0"),
            ready.copy(ownerOptIn = null), ready.copy(ownerOptIn = "0"),
            ready.copy(tvUserSetupComplete = "0"),
        )
        blocked.forEach { env ->
            val store = Store().apply { this.env = env }
            assertEquals(env.toString(), FirmwareBootstrapResult.WAITING, enrollFirmwareHands(store))
            assertNull(store.services)
            assertNull(store.marker)
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `stock sideload remains inert even when a shell granted the requested permissions`() {
        val store = Store().apply { env = ready.copy(systemApp = false) }
        assertEquals(FirmwareBootstrapResult.WAITING, enrollFirmwareHands(store))
        assertTrue(store.writes.isEmpty())
    }

    @Test fun `unrecognized gate values never mean yes`() {
        for (value in listOf("", "true", "yes", "01", " 1", "2")) {
            for (env in listOf(ready.copy(ownerOptIn = value), ready.copy(deviceProvisioned = value),
                ready.copy(userSetupComplete = value), ready.copy(tvUserSetupComplete = value))) {
                val store = Store().apply { this.env = env }
                assertEquals(FirmwareBootstrapResult.WAITING, enrollFirmwareHands(store))
                assertTrue(store.writes.isEmpty())
            }
        }
    }

    @Test fun `TV wizard readiness is optional only when that setting is absent`() {
        val store = Store().apply { env = ready.copy(tvUserSetupComplete = null) }
        assertEquals(FirmwareBootstrapResult.ENABLED, enrollFirmwareHands(store))
    }

    @Test fun `normal setup transitions wait without consuming the opportunity`() {
        val store = Store().apply { env = ready.copy(deviceProvisioned = "0", userSetupComplete = "0") }
        assertEquals(FirmwareBootstrapResult.WAITING, enrollFirmwareHands(store))
        store.env = ready.copy(userSetupComplete = "0")
        assertEquals(FirmwareBootstrapResult.WAITING, enrollFirmwareHands(store))
        assertTrue(store.writes.isEmpty())
        store.env = ready
        assertEquals(FirmwareBootstrapResult.ENABLED, enrollFirmwareHands(store))
    }

    @Test fun `every existing marker including corrupt or zero is terminal`() {
        for (value in listOf("1", "0", "", "unknown")) {
            val store = Store().apply { marker = value }
            assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(store))
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `claim failure never enables accessibility`() {
        val store = Store().apply { claimSucceeds = false }
        assertEquals(FirmwareBootstrapResult.FAILED, enrollFirmwareHands(store))
        assertEquals(listOf("attempt"), store.writes)
        assertNull(store.services)
    }

    @Test fun `unverified claim never enables accessibility`() {
        val store = Store().apply { afterClaim = { marker = null } }
        assertEquals(FirmwareBootstrapResult.FAILED, enrollFirmwareHands(store))
        assertEquals(listOf("attempt"), store.writes)
        assertNull(store.services)
    }

    @Test fun `settings change during the claim is preserved and never retried`() {
        val store = Store().apply {
            services = "other.app/.Reader"
            afterClaim = { services = "third.app/.Reader" }
        }
        assertEquals(FirmwareBootstrapResult.FAILED, enrollFirmwareHands(store))
        assertEquals("third.app/.Reader", store.services)
        assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(store))
        assertEquals(listOf("attempt"), store.writes)
    }

    @Test fun `consent revocation or relock during the claim aborts the write`() {
        for (env in listOf(ready.copy(ownerOptIn = "0"), ready.copy(deviceLocked = true),
            ready.copy(userUnlocked = false), ready.copy(userSetupComplete = "0"))) {
            val store = Store().apply { afterClaim = { this.env = env } }
            assertEquals(FirmwareBootstrapResult.FAILED, enrollFirmwareHands(store))
            assertEquals(listOf("attempt"), store.writes)
            assertNull(store.services)
        }
    }

    @Test fun `failed enablement consumes the opportunity and never rolls back others`() {
        val store = Store().apply { services = "other.app/.Reader"; writeSucceeds = false }
        assertEquals(FirmwareBootstrapResult.FAILED, enrollFirmwareHands(store))
        assertEquals("other.app/.Reader", store.services)
        assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(store))
        assertEquals(listOf("attempt", "services"), store.writes)
    }

    @Test fun `unreadable settings fail closed without exceptions escaping startup`() {
        val store = object : FirmwareBootstrapStore {
            override fun environment(): FirmwareEnvironment = throw SecurityException("denied")
            override fun attempted(): String? = error("must not read")
            override fun markAttempted(): Boolean = error("must not write")
            override fun enabledServices(): String? = error("must not read")
            override fun putEnabledServices(value: String): Boolean = error("must not write")
        }
        assertEquals(FirmwareBootstrapResult.FAILED, enrollFirmwareHands(store))
    }

    @Test fun `appliance image default enrolls after setup without a separate provisioner`() {
        val store = Store().apply { env = ready.copy(ownerOptIn = null, imageEnrollmentDefault = true) }
        assertEquals(FirmwareBootstrapResult.ENABLED, enrollFirmwareHands(store))
        store.services = null // Owner disables the service after enrollment.
        assertEquals(FirmwareBootstrapResult.ALREADY_ATTEMPTED, enrollFirmwareHands(store))
        assertNull(store.services)
    }

    @Test fun `explicit provisioner opt out overrides the appliance default`() {
        for (value in listOf("0", "", "unknown")) {
            val store = Store().apply { env = ready.copy(ownerOptIn = value, imageEnrollmentDefault = true) }
            assertEquals(FirmwareBootstrapResult.WAITING, enrollFirmwareHands(store))
            assertTrue(store.writes.isEmpty())
        }
    }
}
