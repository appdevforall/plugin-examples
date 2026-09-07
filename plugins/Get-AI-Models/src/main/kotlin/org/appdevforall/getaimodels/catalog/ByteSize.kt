package org.appdevforall.getaimodels.catalog

import java.util.Locale

/** Byte-count formatting shared by the row spec line and the expanded detail. */
object ByteSize {

    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private const val MIB = 1024.0 * 1024.0

    /** e.g. 2497276320 -> "2.33 GB"; values under 1 GiB render as whole MB. */
    fun format(bytes: Long): String = when {
        bytes >= GIB -> String.format(Locale.US, "%.2f GB", bytes / GIB)
        else -> String.format(Locale.US, "%.0f MB", bytes / MIB)
    }

    /** Whole-GB form used for the informational minimum-RAM line, e.g. "6 GB". */
    fun formatWholeGb(bytes: Long): String =
        String.format(Locale.US, "%.0f GB", bytes / GIB)
}
