package dk.lifelist.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

    /**
     * The coordinates a photograph is carrying, if any.
     *
     * A picture chosen from the gallery usually knows where it was taken, and that is better
     * evidence of where the sighting happened than wherever the phone is standing now — which
     * may be a sofa, three days later.
     */
    fun coordinatesOf(context: Context, uri: Uri): Pair<Double, Double>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // androidx's ExifInterface returns the pair or null; the platform one wanted a
            // FloatArray to fill in. Using the androidx artefact everywhere keeps this one
            // shape and works back to API 19.
            ExifInterface(stream).latLong?.let { it[0] to it[1] }
        }
    }.getOrNull()
}
