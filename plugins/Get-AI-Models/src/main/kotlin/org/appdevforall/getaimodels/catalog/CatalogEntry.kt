package org.appdevforall.getaimodels.catalog

/**
 * One downloadable file: a (model, quantization) pair, not a model, so two quantizations are two
 * entries. Every field comes from the catalog asset - nothing is computed on device or fetched at
 * runtime. See docs/CURATION.md for the admission gates an entry has to clear.
 */
data class CatalogEntry(
    /** Stable id, unique within the catalog. Used as the download-state key. */
    val id: String,
    /** Human-readable model name, e.g. "Qwen3 4B". */
    val name: String,
    /** GGUF quantization of this specific file, e.g. "Q4_K_M". Any quantization is allowed. */
    val quantization: String,
    /** Parameter count as published, e.g. "4B" - a label, not a number. */
    val parameters: String,
    /** Destination file name under /sdcard/Download. */
    val fileName: String,
    /** Exact on-disk size of the .gguf in bytes, as published by the host repository. */
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the file. A hard gate: a mismatch deletes the download. */
    val sha256: String,
    /** Direct single-file HTTPS download URL, pinned to a specific repository revision. */
    val url: String,
    /** Informational minimum device RAM. Display-only - the device's RAM is never checked. */
    val minRamBytes: Long,
    /** Who publishes this GGUF file (the re-uploader, not only the base model author). */
    val publisher: String,
    /** Context window in tokens, as published by the base model. */
    val contextTokens: Int,
    /** SPDX-ish license id of the base model and this GGUF re-upload, e.g. "apache-2.0". */
    val license: String,
    /** Repository id of the base model the GGUF was converted from. */
    val baseModel: String,
    /** Strengths-and-weaknesses paragraph shown when the row is expanded. */
    val description: String,
    /** True once gates 3 and 4 (loads in the AI plugin, tool-use eval) are proven by the harness. */
    val behaviouralGatesVerified: Boolean
)
