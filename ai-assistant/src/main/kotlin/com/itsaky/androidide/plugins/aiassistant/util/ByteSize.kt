package com.itsaky.androidide.plugins.aiassistant.util

import java.util.Locale

/**
 * Formats byte counts for display, in binary units and the US locale. Mirrors ai-core's `ByteSize`,
 * unreachable from here across isolated plugin classloaders — keep the units in step, since both
 * describe the same memory and mixing binary with decimal would read as a contradiction.
 */
internal object ByteSize {

    private const val BYTES_PER_MB = 1024.0 * 1024.0
    private const val BYTES_PER_GB = BYTES_PER_MB * 1024.0

    /**
     * Formats [bytes] with the largest unit that keeps the figure meaningful; sub-gigabyte values
     * stay in MB, since "0.3 GB free" reads as a broken string rather than as a shortage.
     *
     * @param bytes a byte count
     * @return the size as a one-decimal "X.X GB" or "X.X MB" string
     */
    fun format(bytes: Long): String =
        if (bytes >= BYTES_PER_GB) String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB)
        else String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
}
