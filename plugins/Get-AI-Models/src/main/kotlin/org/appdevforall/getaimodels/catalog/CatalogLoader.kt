package org.appdevforall.getaimodels.catalog

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * Reads the static catalog bundled as an asset; there is no remote one. A parse failure is therefore
 * a packaging bug rather than a runtime condition, so [parse] rejects a malformed catalog loudly
 * instead of silently dropping rows.
 */
object CatalogLoader {

    const val ASSET_PATH = "catalog/models.json"

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    fun load(assets: AssetManager): List<CatalogEntry> =
        assets.open(ASSET_PATH).use { parse(it.reader().readText()) }

    /** Parses catalog JSON. Throws [IllegalArgumentException] if any entry is incomplete. */
    fun parse(json: String): List<CatalogEntry> {
        val root = JSONObject(json)
        val models = root.getJSONArray("models")
        require(models.length() > 0) { "catalog contains no models" }

        val entries = (0 until models.length()).map { index ->
            val o = models.getJSONObject(index)
            val id = o.getString("id")
            val entry = CatalogEntry(
                id = id,
                name = o.getString("name"),
                quantization = o.getString("quantization"),
                parameters = o.getString("parameters"),
                fileName = o.getString("fileName"),
                sizeBytes = o.getLong("sizeBytes"),
                sha256 = o.getString("sha256").lowercase(),
                url = o.getString("url"),
                minRamBytes = o.getLong("minRamBytes"),
                publisher = o.getString("publisher"),
                contextTokens = o.getInt("contextTokens"),
                license = o.getString("license"),
                baseModel = o.getString("baseModel"),
                description = o.getString("description"),
                // getBoolean, not optBoolean: a new entry must state its gate status explicitly.
                behaviouralGatesVerified = o.getBoolean("behaviouralGatesVerified")
            )
            validate(entry, index)
            entry
        }

        val duplicates = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate catalog ids: $duplicates" }
        return entries
    }

    private fun validate(entry: CatalogEntry, index: Int) {
        fun bad(reason: String): Nothing =
            throw IllegalArgumentException("catalog entry #$index (${entry.id}): $reason")

        if (entry.id.isBlank()) bad("blank id")
        if (entry.name.isBlank()) bad("blank name")
        if (entry.quantization.isBlank()) bad("blank quantization")
        if (!entry.fileName.endsWith(".gguf")) bad("fileName is not a .gguf: ${entry.fileName}")
        // A path separator in fileName would let the catalog write outside /sdcard/Download.
        if (entry.fileName.contains('/') || entry.fileName.contains('\\')) {
            bad("fileName must not contain a path separator: ${entry.fileName}")
        }
        if (entry.sizeBytes <= 0) bad("sizeBytes must be positive")
        if (!SHA256_HEX.matches(entry.sha256)) bad("sha256 is not 64 lower-case hex chars")
        // HTTPS only: the checksum protects integrity, but the URL still must not be cleartext.
        if (!entry.url.startsWith("https://")) bad("url is not https: ${entry.url}")
        if (entry.minRamBytes <= 0) bad("minRamBytes must be positive")
        if (entry.publisher.isBlank()) bad("blank publisher")
        if (entry.description.isBlank()) bad("blank description")
        if (entry.contextTokens <= 0) bad("contextTokens must be positive")
    }
}
