package dk.lifelist.app

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading `birdnet_classes.json` — output-vector position → GBIF taxon key.
 *
 * **Index order is the contract.** A label file that gains or loses a class shifts every index
 * after it and would misattribute every detection past that point, quietly and plausibly. The
 * artefact records the BirdNET version it was built from for that reason, and BUILD.md §3.1
 * makes a version bump a deliberate rebuild rather than a drift.
 *
 * A malformed entry is skipped rather than fatal: one unreadable class should cost that class,
 * not the whole listening screen.
 */
object AudioAssets {

    private val json = Json { ignoreUnknownKeys = true }

    data class Classes(
        val birdnetVersion: String,
        val region: String,
        val byIndex: Map<Int, Int>,
    )

    fun loadClasses(context: Context, asset: String = Listener.CLASSES_ASSET): Map<Int, Int> =
        parse(context.assets.open(asset).use { it.readBytes().decodeToString() }).byIndex

    /** Separated from the asset so the parsing is testable without a device. */
    fun parse(text: String): Classes {
        val root = json.parseToJsonElement(text).jsonObject
        val byIndex = (root["classes"]?.jsonObject ?: return Classes("unknown", "unknown", emptyMap()))
            .mapNotNull { (index, value) ->
                val position = index.toIntOrNull() ?: return@mapNotNull null
                val taxonId = value.jsonPrimitive.content.toIntOrNull() ?: return@mapNotNull null
                position to taxonId
            }
            .toMap()
        return Classes(
            birdnetVersion = root["birdnet_version"]?.jsonPrimitive?.content ?: "unknown",
            region = root["region"]?.jsonPrimitive?.content ?: "unknown",
            byIndex = byIndex,
        )
    }
}
