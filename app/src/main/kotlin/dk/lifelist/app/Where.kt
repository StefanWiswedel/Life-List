package dk.lifelist.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Where a sighting happened, cheaply and optionally.
 *
 * `LocationManager.getLastKnownLocation` rather than Play Services: this app has no other
 * Google dependency, works offline by design, and a life list does not need a live fix — it
 * needs roughly where you were standing. Coarse permission only, and a record without a
 * location is a perfectly good record.
 *
 * Every path returns null rather than throwing. A missing fix must never cost a sighting.
 */
object Where {

    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(context: Context): Location? {
        if (!granted(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    /** "55.676, 12.568" — enough to find it again on a map, not a claim of precision. */
    fun format(latitude: Double, longitude: Double): String =
        "%.3f, %.3f".format(latitude, longitude)
}
