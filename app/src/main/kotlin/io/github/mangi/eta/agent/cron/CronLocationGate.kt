package io.github.mangi.eta.agent.cron

import android.content.Context
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional location condition on a CRON/INTERVAL/ONCE schedule. This is deliberately NOT a real
 * OS-level geofence (no Play Services, no GeofencingClient): the app already has a passive,
 * on-demand [DeviceLocationProvider] that never wakes the GPS or listens continuously, and
 * bolting Play Services Location on top of that would fight the app's own battery-conscious
 * design. Instead, the gate is re-evaluated against the last known location each time the
 * schedule's own timer fires, so it is only as responsive as the schedule's normal interval —
 * never instantaneous the way a push-based geofence callback would be.
 */
internal object CronLocationGate {
    data class Evaluation(val shouldRun: Boolean, val reason: String)

    /** Returns null when the schedule has no location condition attached (gate does not apply). */
    fun evaluate(context: Context, schedule: CronScheduleEntity): Evaluation? {
        val lat = schedule.geoLat ?: return null
        val lng = schedule.geoLng ?: return null
        val radiusM = schedule.geoRadiusM ?: return null
        val transition = schedule.geoTransition ?: return null

        return when (val location = DeviceLocationProvider.latest(context)) {
            is DeviceLocationProvider.Result.Unavailable ->
                Evaluation(shouldRun = false, reason = "location_${location.status}")
            is DeviceLocationProvider.Result.Available -> {
                val distanceM = haversineMeters(location.latitude, location.longitude, lat, lng)
                val inside = distanceM <= radiusM
                val met = when (transition) {
                    "ENTER" -> inside
                    "EXIT" -> !inside
                    else -> true
                }
                Evaluation(
                    shouldRun = met,
                    reason = if (met) "location_condition_met" else "location_condition_not_met",
                )
            }
        }
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }
}
