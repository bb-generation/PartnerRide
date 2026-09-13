package net.bbgen.karoo.partnerride.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTapTest {

    @Test
    fun `tap starts a link that is not running`() {
        assertEquals(FieldTapAction.START_LINK, fieldTapAction(demoMode = false, linkRunning = false))
    }

    @Test
    fun `tap stops a running link`() {
        assertEquals(FieldTapAction.STOP_LINK, fieldTapAction(demoMode = false, linkRunning = true))
    }

    @Test
    fun `tap in demo mode leaves demo mode and leaves a running link alone`() {
        assertEquals(FieldTapAction.LEAVE_DEMO_MODE, fieldTapAction(demoMode = true, linkRunning = true))
    }

    /** Leaving demo mode must not start a stopped link as a side effect. */
    @Test
    fun `tap in demo mode while stopped only leaves demo mode`() {
        assertEquals(FieldTapAction.LEAVE_DEMO_MODE, fieldTapAction(demoMode = true, linkRunning = false))
    }
}
