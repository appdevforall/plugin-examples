package org.appdevforall.getaimodels.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the *shipped* catalog asset, not a fixture. Curation is hand-editing models.json, where a
 * typo yields a plugin that shows nothing or downloads a file it can never verify - so the real asset
 * is parsed and validated on every build.
 */
class CatalogLoaderTest {

    private val asset = File("src/main/assets/${CatalogLoader.ASSET_PATH}")

    private fun catalog(): List<CatalogEntry> = CatalogLoader.parse(asset.readText())

    @Test
    fun givenTheShippedCatalogAsset_whenParsed_thenEntriesAreLoaded() {
        assertTrue("missing asset: ${asset.absolutePath}", asset.isFile)
        assertTrue("catalog is empty", catalog().isNotEmpty())
    }

    @Test
    fun givenTheShippedCatalogAsset_whenUrlsAreInspected_thenEachPinsACommitRevision() {
        // A /resolve/main/ URL would let the file change under a pinned sha256.
        catalog().forEach { entry ->
            assertTrue(
                "${entry.id}: url must not resolve a branch: ${entry.url}",
                !entry.url.contains("/resolve/main/")
            )
            assertTrue(
                "${entry.id}: url must end in the catalogued file name",
                entry.url.endsWith("/${entry.fileName}")
            )
        }
    }

    @Test
    fun givenTheShippedCatalogAsset_whenStructureIsChecked_thenEveryEntryIsWellFormed() {
        // The open-licence and 16k-context gates are deliberately NOT asserted: the ticket owner
        // waived both for the current catalog, and docs/CURATION.md records which entries break them.
        catalog().forEach { entry ->
            // Gate 6 still holds for every entry: one file, no split-GGUF parts.
            assertTrue(
                "${entry.id}: looks like a split GGUF part: ${entry.fileName}",
                !Regex("-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(entry.fileName)
            )
            assertTrue("${entry.id}: context window must be positive", entry.contextTokens > 0)
        }
    }

    @Test
    fun givenTheShippedCatalogAsset_whenGateStatusIsRead_thenEveryEntryDeclaresIt() {
        // The harness does not exist yet, so no entry may claim the behavioural gates.
        catalog().forEach { entry ->
            assertTrue(
                "${entry.id}: behavioural gates cannot be claimed until the harness proves them",
                !entry.behaviouralGatesVerified
            )
        }
    }

    @Test
    fun givenAnEntryMissingItsGateStatus_whenParsed_thenParsingIsRejected() {
        // The field is last in each entry, so the preceding comma goes with it.
        val broken = asset.readText()
            .replace(Regex(",\\s*\"behaviouralGatesVerified\"\\s*:\\s*(true|false)"), "")
        val failure = runCatching { CatalogLoader.parse(broken) }.exceptionOrNull()
        assertTrue("expected a parse failure, got $failure", failure != null)
    }

    @Test
    fun givenACatalogWhoseChecksumIsNotASha256_whenParsed_thenParsingIsRejected() {
        val broken = asset.readText().replace(catalog().first().sha256, "not-a-hash")
        val failure = runCatching { CatalogLoader.parse(broken) }.exceptionOrNull()
        assertTrue("expected a parse failure, got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun givenByteCounts_whenFormatted_thenTheSpecLineUnitsAreProduced() {
        assertEquals("2.33 GB", ByteSize.format(2_497_280_256L))
        assertEquals("610 MB", ByteSize.format(639_446_688L))
        assertEquals("6 GB", ByteSize.formatWholeGb(6L * 1024 * 1024 * 1024))
    }
}
