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
        val photoPath: String? = null,
        val threshold: Float = 0.70f,
        val modelVersion: String = "unknown",
        val determinedBy: String = "MODEL",
        val refinedFrom: Int? = null,
        val confidence: Float? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    private fun Stored.toRecord() = Record(
        id, taxonId, observedAt, photoPath, threshold, modelVersion,
        runCatching { Determiner.valueOf(determinedBy) }.getOrDefault(Determiner.MODEL),
        refinedFrom, confidence, latitude, longitude,
    )

    private fun Record.toStored() = Stored(
        id, taxonId, observedAt, photoPath, threshold, modelVersion, determinedBy.name,
        refinedFrom, confidence, latitude, longitude,
    )

    fun load(): List<Record> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(ListSerializer(Stored.serializer()), file.readText()).map { it.toRecord() }
    }.getOrElse {
        // A corrupt list must not stop the app opening. Better an empty screen the user can
        // rebuild than a crash loop they cannot get past.
        emptyList()
    }

    fun save(records: List<Record>) {
        val temporary = File(context.filesDir, "life-list.json.tmp")
        val payload: List<Stored> = records.map { it.toStored() }
        temporary.writeText(json.encodeToString(ListSerializer(Stored.serializer()), payload))
        temporary.renameTo(file)
    }

    /** Store the photograph beside the record; a life list without its photos is a spreadsheet. */
    fun savePhoto(bitmap: Bitmap): String {
        val destination = File(photos, "${UUID.randomUUID()}.jpg")
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return destination.absolutePath
    }

    fun add(record: Record): List<Record> = (load() + record).also { save(it) }

    fun newId(): String = UUID.randomUUID().toString()
}
