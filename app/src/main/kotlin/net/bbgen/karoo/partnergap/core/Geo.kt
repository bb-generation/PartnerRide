package net.bbgen.karoo.partnergap.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val latDeg: Double, val lonDeg: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Straight-line (great-circle) distance in meters. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees [0, 360). 0 = north. */
    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference a - b, normalized to [-180, 180). */
    fun angleDiffDeg(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d >= 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Dead-reckons a position [dtSeconds] forward along [bearingDeg] at [speedMps] using a
     * flat-earth local tangent projection — sufficient for the few seconds of extrapolation
     * the gap computation ever needs.
     */
    fun extrapolate(
        latDeg: Double,
        lonDeg: Double,
        speedMps: Double,
        bearingDeg: Double,
        dtSeconds: Double,
    ): LatLon {
        val distance = speedMps * dtSeconds
        val bearing = Math.toRadians(bearingDeg)
        val dNorth = distance * cos(bearing)
        val dEast = distance * sin(bearing)
        val newLat = latDeg + Math.toDegrees(dNorth / EARTH_RADIUS_M)
        val newLon = lonDeg + Math.toDegrees(dEast / (EARTH_RADIUS_M * cos(Math.toRadians(latDeg))))
        return LatLon(newLat, newLon)
    }
}
