package net.bbgen.karoo.partnergap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `extrapolating due east moves the expected distance at the expected bearing`() {
        val p = Geo.extrapolate(47.0, 15.0, speedMps = 10.0, bearingDeg = 90.0, dtSeconds = 2.0)
        assertEquals(20.0, Geo.haversineMeters(47.0, 15.0, p.latDeg, p.lonDeg), 0.05)
        assertEquals(90.0, Geo.initialBearingDeg(47.0, 15.0, p.latDeg, p.lonDeg), 0.5)
        assertEquals(47.0, p.latDeg, 1e-6) // due east: latitude unchanged
    }

    @Test
    fun `extrapolating due north moves only latitude`() {
        val p = Geo.extrapolate(47.0, 15.0, speedMps = 12.5, bearingDeg = 0.0, dtSeconds = 3.0)
        assertEquals(37.5, Geo.haversineMeters(47.0, 15.0, p.latDeg, p.lonDeg), 0.05)
        assertEquals(15.0, p.lonDeg, 1e-9)
    }

    @Test
    fun `extrapolating diagonally matches distance and bearing`() {
        val p = Geo.extrapolate(-33.87, 151.21, speedMps = 8.0, bearingDeg = 225.0, dtSeconds = 2.5)
        assertEquals(20.0, Geo.haversineMeters(-33.87, 151.21, p.latDeg, p.lonDeg), 0.05)
        assertEquals(225.0, Geo.initialBearingDeg(-33.87, 151.21, p.latDeg, p.lonDeg), 0.5)
    }

    @Test
    fun `zero speed or zero time is the identity`() {
        val still = Geo.extrapolate(47.0, 15.0, speedMps = 0.0, bearingDeg = 90.0, dtSeconds = 3.0)
        assertEquals(47.0, still.latDeg, 1e-12)
        assertEquals(15.0, still.lonDeg, 1e-12)
        val instant = Geo.extrapolate(47.0, 15.0, speedMps = 10.0, bearingDeg = 90.0, dtSeconds = 0.0)
        assertEquals(47.0, instant.latDeg, 1e-12)
        assertEquals(15.0, instant.lonDeg, 1e-12)
    }
}
