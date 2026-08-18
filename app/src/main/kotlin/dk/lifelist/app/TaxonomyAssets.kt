package dk.lifelist.app

import android.content.Context
import dk.lifelist.core.Taxon
import dk.lifelist.core.Taxonomy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loading the shipped taxonomy and model metadata.
 *
 * `Taxonomy`'s constructor asserts all five spec invariants, so a malformed asset fails at
 * startup with a clear message rather than at inference with a wrong answer. Spec §6 asks for
 * exactly that: fail loudly at startup, not subtly later.
 */
object TaxonomyAssets {

    private val json = Json { ignoreUnknownKeys = true }

    data class Meta(val specVersion: Int, val temperature: Float, val nTaxa: Int, val version: String)

    fun loadTaxonomy(context: Context, asset: String = "taxonomy.json"): Taxonomy {
        val text = context.assets.open(asset).use { it.readBytes().decodeToString() }
        val taxa = json.parseToJsonElement(text).jsonArray.map { element ->
            val o = element.jsonObject
            fun int(key: String) = o[key]?.jsonPrimitive?.takeIf { it.content != "null" }
                ?.content?.toIntOrNull()
            fun str(key: String) = o[key]?.jsonPrimitive?.takeIf { it.content != "null" }?.content

            Taxon(
                taxonId = int("taxon_id")!!,
                parentId = int("parent_id"),
                rank = str("rank")!!,
                scientificName = str("scientific_name")!!,
                vernacularDa = str("vernacular_da"),
                vernacularEn = str("vernacular_en"),
                leafIndex = int("leaf_index"),
            )
        }
        return Taxonomy(taxa)
    }

    fun loadMeta(context: Context, asset: String = "model_meta.json"): Meta {
        val o = json.parseToJsonElement(
            context.assets.open(asset).use { it.readBytes().decodeToString() }
        ).jsonObject
        val spec = o["spec_version"]!!.jsonPrimitive.content.toInt()
        require(spec == SUPPORTED_SPEC_VERSION) {
            "model asset declares spec_version $spec; this build implements " +
                "$SUPPORTED_SPEC_VERSION. Refusing to load rather than guess."
        }
        return Meta(
            specVersion = spec,
            temperature = o["temperature"]!!.jsonPrimitive.content.toFloat(),
            nTaxa = o["n_taxa"]!!.jsonPrimitive.content.toInt(),
            version = o["model_version"]?.jsonPrimitive?.content ?: "unknown",
        )
    }

    const val SUPPORTED_SPEC_VERSION = 1
}
