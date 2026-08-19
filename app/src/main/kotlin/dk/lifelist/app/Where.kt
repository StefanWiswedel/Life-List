package dk.lifelist.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where a sighting happened.
 *
 * The first version asked for permission only *after* the first keep, and then read
 * `getLastKnownLocation` — which is null on a phone where nothing has asked for a fix
 * recently. So the permission dialog appeared too late to help the record that triggered it,
 * and even once granted the field usually stayed empty. Reported as "the location is not
 * filling in", and it was three separate faults:
 *
 * 1. **Asked too late.** Permission is now requested when the camera opens, alongside the one
 *    the user is already granting, so a fix exists by the time there is something to attach
 *    it to.
 * 2. **Never actually requested a fix.** `lastKnown` is now a fallback behind a real
 *    single-shot request with a short deadline. Two seconds of waiting beats an empty field.
 * 3. **A photograph knows better than the phone.** A picture chosen from the gallery carries
 *    its own coordinates, and those beat wherever the phone is standing now — see
 *    `Gallery.coordinatesOf`.
 *
 * Still `LocationManager` rather than Play Services: this app has no other Google dependency
 * and works offline by design. A life list does not need a survey-grade fix, it needs roughly
 * where you were standing. Everything returns null rather than throwing — a missing fix must
 * never cost a sighting.
 */
object Where {

    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    /** How long a sighting is worth waiting for a fix. Beyond this, last-known will do. */
    private const val DEADLINE_MS = 2_500L

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun lastKnown(context: Context): Location? {
        if (!granted(context)) return null
        val manager = manager(context) ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    /**
     * A fix now, or the best one already lying around.
     *
     * Blocks for at most [DEADLINE_MS], so it must not run on the main thread. Both callers
     * are already off it — keeping a record writes a file anyway.
     */
    fun current(context: Context): Location? {
        if (!granted(context)) return null
        val manager = manager(context) ?: return lastKnown(context)

        val fresh = runCatching {
            val latch = CountDownLatch(1)
            var found: Location? = null
            @Suppress("MissingPermission")
            manager.requestSingleUpdate(
                LocationManager.NETWORK_PROVIDER.takeIf {
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                } ?: LocationManager.GPS_PROVIDER,
                { location -> found = location; latch.countDown() },
                context.mainLooper,
            )
            latch.await(DEADLINE_MS, TimeUnit.MILLISECONDS)
            found
        }.getOrNull()

        return fresh ?: lastKnown(context)
    }

    /**
     * "Vanløse, Copenhagen" — a place a person recognises.
     *
     * Coordinates are a fact and not a memory. Reverse-geocoded once, when the record is made,
     * and then stored: `Geocoder` needs the network on most devices, and a field that empties
     * itself on a walk with no signal is worse than one that never filled.
     *
     * Deprecated blocking overload on purpose. The API 33 callback version exists, and using
     * it would mean two code paths and a callback that fires after the record is already
     * written. Called off the main thread, where the deprecation is about not blocking.
     */
    @Suppress("DEPRECATION")
    fun describe(context: Context, latitude: Double, longitude: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val address = Geocoder(context, Locale.UK)
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?: return null
        // subLocality is the suburb where one is known; locality is the town. Both, when both
        // exist, because "Vanløse" alone means nothing to anyone who does not live there.
        listOfNotNull(
            address.subLocality,
            address.locality ?: address.subAdminArea,
        ).distinct().joinToString(", ").ifBlank { address.countryName }
    }.getOrNull()

    /** "55.676, 12.568" — enough to find it again on a map, not a claim of precision. */
    fun format(latitude: Double, longitude: Double): String =
        "%.4f, %.4f".format(latitude, longitude)

    /**
     * Open the sighting in whatever map app the phone has.
     *
     * Not an inline map: that means Google Maps SDK and an API key checked into a public
     * repository, or a second tile source and its licence. A `geo:` intent is one line, works
     * offline-ish, and lands in the app the user already knows.
     */
    fun openInMaps(context: Context, latitude: Double, longitude: Double, label: String?) {
        val point = "$latitude,$longitude"
        val query = label?.let { Uri.encode(it) } ?: Uri.encode(point)
        val uri = Uri.parse("geo:$point?q=$point($query)")
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=16/$latitude/$longitude"),
                    )
                )
            }
        }
    }

    /** True when this build can turn coordinates into a name at all. */
    val geocoding: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Geocoder.isPresent()
}
