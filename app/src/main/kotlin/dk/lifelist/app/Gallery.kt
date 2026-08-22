package dk.lifelist.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The photographs, in the place a photograph belongs.
 *
 * Asked directly: "if I take a photo in the app, does it add it to my regular camera roll? so
 * I don't lose the photo if I go back to the wrong screen." It did not, and the answer was
 * worse than that — an un-kept capture existed only as a bitmap in memory, so backing out of
 * the result screen destroyed it.
 *
 * Every shutter press now writes a JPEG to `Pictures/Life List` through MediaStore, which
 * needs no permission from API 29 and puts it in the gallery immediately. Losing a photograph
 * to a wrong tap is not a trade-off anyone agreed to.
 */
object Gallery {

    private const val FOLDER = "Pictures/Life List"

    fun save(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.UK).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lifelist-$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }.getOrNull()

    /** Held from API 29; below that the EXIF is never redacted and this is not needed. */
    val MEDIA_LOCATION_PERMISSION: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_MEDIA_LOCATION
        } else {
            null
        }

    fun canReadPhotoLocation(context: Context): Boolean {
        val permission = MEDIA_LOCATION_PERMISSION ?: return true
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * The coordinates a photograph is carrying, if any.
     *
     * A picture chosen from the gallery usually knows where it was taken, and that is better
     * evidence of where the sighting happened than wherever the phone is standing now — which
     * may be a sofa, three days later.
     *
     * **This silently returned null for every gallery photograph.** From API 29, MediaStore and
     * the photo picker hand out URIs with the location tags *stripped*, unless the app holds
     * `ACCESS_MEDIA_LOCATION` and asks for the unredacted original by name. Without both, the
     * EXIF read here always came back empty and the caller quietly fell back to the phone's
     * current fix — so a moth photographed last week in another county was filed at wherever
     * you happened to be standing when you opened the app, with nothing on screen to say so.
     * A wrong coordinate is worse than an absent one: it looks like data.
     *
     * `setRequireOriginal` is the ask. It throws if the permission is missing rather than
     * degrading, which is why the permission is checked first and the original URI is the
     * fallback — on a phone where the user declined, this returns null and the caller can say
     * so, which is the honest outcome.
     */
    fun coordinatesOf(context: Context, uri: Uri): Pair<Double, Double>? = runCatching {
        val resolver = context.contentResolver
        val original =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canReadPhotoLocation(context)) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }
        resolver.openInputStream(original)?.use { stream ->
            // androidx's ExifInterface returns the pair or null; the platform one wanted a
            // FloatArray to fill in. Using the androidx artefact everywhere keeps this one
            // shape and works back to API 19.
            ExifInterface(stream).latLong?.let { it[0] to it[1] }
        }
    }.getOrNull()
}
