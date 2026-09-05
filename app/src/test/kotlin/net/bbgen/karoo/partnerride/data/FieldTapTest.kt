package net.bbgen.karoo.partnerride.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTapTest {

    @Test
    fun `tap toggles enabled off`() {
        val after = PartnerRideSettings(enabled = true).afterFieldTap()
        assertFalse(after.enabled)
    }

    @Test
    fun `tap toggles enabled on`() {
        val after = PartnerRideSettings(enabled = false).afterFieldTap()
        assertTrue(after.enabled)
    }

    @Test
    fun `tap in demo mode leaves demo mode and does not touch enabled`() {
        val after = PartnerRideSettings(enabled = true, demoMode = true).afterFieldTap()
        assertFalse(after.demoMode)
        assertTrue(after.enabled)
    }

    /** Leaving demo mode must not switch a disabled link on as a side effect. */
    @Test
    fun `tap in demo mode while disabled only leaves demo mode`() {
        val after = PartnerRideSettings(enabled = false, demoMode = true).afterFieldTap()
        assertFalse(after.demoMode)
        assertFalse(after.enabled)
    }

    /** Two taps out of demo mode: the first exits it, the second starts toggling again. */
    @Test
    fun `second tap after leaving demo mode toggles enabled`() {
        val after = PartnerRideSettings(enabled = true, demoMode = true)
            .afterFieldTap()
            .afterFieldTap()
        assertFalse(after.demoMode)
        assertFalse(after.enabled)
    }

    @Test
    fun `tap leaves every other setting alone`() {
        val before = PartnerRideSettings(
            enabled = true,
            coupleCode = "123456",
            alertEnabled = true,
            alertThresholdMeters = 250,
        )
        val after = before.afterFieldTap()
        assertEquals(before.copy(enabled = false), after)
    }
}
