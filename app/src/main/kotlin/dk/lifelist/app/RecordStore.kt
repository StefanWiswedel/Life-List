package dk.lifelist.app

import android.content.Context
import android.graphics.Bitmap
import dk.lifelist.core.Determiner
import dk.lifelist.core.Record
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Where the life list lives.
 *
 * A JSON file and a photo directory, not a database. The list is hundreds of rows on a
 * personal device, and Room would be a schema, a migration story and a DAO in exchange for
 * nothing. When it stops being hundreds of rows this is a small thing to replace.
 *
 * Writes are atomic — temp file then rename — because the alternative is a truncated list
 * after a kill, and a life list that loses records is worse than one that never existed.
 */
class RecordStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(context.filesDir, "life-list.json")
    private val photos = File(context.filesDir, "photos").apply { mkdirs() }

    @Serializable
    private data class Stored(
        val id: String,
        val taxonId: Int,
        val observedAt: Long,
        // `photoPath` is the pre-0.8 field. Read, never written: a list on disk that
        // silently loses the reader's old single photograph is the one bug a store must not
        // have. `photoPaths` is what everything writes now.
        val photoPath: String? = null,
        val photoPaths: List<String> = emptyList(),
        val place: String? = null,
        val threshold: Float = 0.70f,
        val modelVersion: String = "unknown",
        val determinedBy: String = "MODEL",
        val refinedFrom: Int? = null,
        val confidence: Float? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    private fun Stored.toRecord() = Record(
        id = id,
        taxonId = taxonId,
        observedAt = observedAt,
        photoPaths = if (photoPaths.isNotEmpty()) photoPaths else listOfNotNull(photoPath),
        threshold = threshold,
        modelVersion = modelVersion,
        determinedBy = runCatching { Determiner.valueOf(determinedBy) }
            .getOrDefault(Determiner.MODEL),
        refinedFrom = refinedFrom,
        confidence = confidence,
        latitude = latitude,
        longitude = longitude,
        place = place,
    )

    private fun Record.toStored() = Stored(
        id = id,
        taxonId = taxonId,
        observedAt = observedAt,
        photoPath = null,
        photoPaths = photoPaths,
        place = place,
        threshold = threshold,
        modelVersion = modelVersion,
        determinedBy = determinedBy.name,
        refinedFrom = refinedFrom,
        confidence = confidence,
        latitude = latitude,
        longitude = longitude,
    )

    fun load(): List<Record> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(ListSerializer(Stored.serializer()), file.readText()).map { it.toRecord() }
    }.getOrElse {
        // A corrupt list must not stop the app opening. Better an empty screen the user can
        // rebuild than a crash loop they cannot get past.
        emptyList()
    }

    /**
     * Returns false if the list could not be written.
     *
     * Never throws. A full disk on the walk home is a reason to tell someone their sighting
     * did not save; it is not a reason to take the app down mid-gesture, which is how the
     * write path turns into a crash the user reads as "the app is broken".
     */
    fun save(records: List<Record>): Boolean = runCatching {
        val temporary = File(context.filesDir, "life-list.json.tmp")
        val payload: List<Stored> = records.map { it.toStored() }
        temporary.writeText(json.encodeToString(ListSerializer(Stored.serializer()), payload))
        temporary.renameTo(file)
    }.isSuccess

    /** Photographs that failed to write are dropped, not raised — the record still lands. */
    fun savePhotos(bitmaps: List<Bitmap>): List<String> =
        bitmaps.mapNotNull { runCatching { savePhoto(it) }.getOrNull() }

    /** Store the photograph beside the record; a life list without its photos is a spreadsheet. */
    fun savePhoto(bitmap: Bitmap): String {
        val destination = File(photos, "${UUID.randomUUID()}.jpg")
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return destination.absolutePath
    }

    fun add(record: Record): List<Record> = (load() + record).also { save(it) }

    /**
     * Replace one record in place, keeping its position in the file.
     *
     * How an identification gets corrected. A record settled to species later is the *same*
     * sighting — same id, same date, same photographs — so it is updated rather than deleted
     * and re-added, which would silently move it to the end of the list and change the day
     * you saw it.
     */
    fun update(record: Record): List<Record> =
        load().map { if (it.id == record.id) record else it }.also { save(it) }

    fun delete(id: String): List<Record> = load().filterNot { it.id == id }.also { save(it) }

    fun newId(): String = UUID.randomUUID().toString()
}
