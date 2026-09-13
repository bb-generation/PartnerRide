package net.bbgen.karoo.partnerride.core

import net.bbgen.karoo.partnerride.core.PermissionRequest.BACKGROUND_LOCATION
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRequestTest {

    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val scan = "android.permission.BLUETOOTH_SCAN"

    @Test
    fun `background location is held back while anything else is missing`() {
        assertEquals(listOf(fine, scan), PermissionRequest.nextBatch(listOf(fine, BACKGROUND_LOCATION, scan)))
    }

    @Test
    fun `background location is requested alone once it is the only thing missing`() {
        assertEquals(listOf(BACKGROUND_LOCATION), PermissionRequest.nextBatch(listOf(BACKGROUND_LOCATION)))
    }

    @Test
    fun `nothing missing requests nothing`() {
        assertEquals(emptyList<String>(), PermissionRequest.nextBatch(emptyList()))
    }

    @Test
    fun `a granted foreground step chains into background location`() {
        assertEquals(
            listOf(BACKGROUND_LOCATION),
            PermissionRequest.followUp(requested = listOf(fine, scan), missing = listOf(BACKGROUND_LOCATION)),
        )
    }

    @Test
    fun `a refused foreground step does not chain`() {
        assertEquals(
            emptyList<String>(),
            PermissionRequest.followUp(requested = listOf(fine, scan), missing = listOf(fine, BACKGROUND_LOCATION)),
        )
    }

    @Test
    fun `a refused background step does not reopen itself`() {
        assertEquals(
            emptyList<String>(),
            PermissionRequest.followUp(
                requested = listOf(BACKGROUND_LOCATION),
                missing = listOf(BACKGROUND_LOCATION),
            ),
        )
    }

    @Test
    fun `nothing left to chain into after everything is granted`() {
        assertEquals(emptyList<String>(), PermissionRequest.followUp(requested = listOf(fine), missing = emptyList()))
    }
}
