package com.itsaky.androidide.plugins.aiagentmcp.testing

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory [SharedPreferences], so the settings store can be exercised off a device.
 *
 * The seam this plugs into exists for one reason — showing that `McpServerStore`'s lock actually
 * serialises a read-modify-write — so [readDelayMillis] is here too: a real preferences read costs
 * a lock and a file, and a race that needs microseconds of window is not a race a test would ever
 * catch on an in-memory map.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = ConcurrentHashMap<String, String>()

    /** How long a read blocks, widening the window a missing lock would lose a write in. */
    @Volatile
    var readDelayMillis: Long = 0

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String, defValue: String?): String? {
        if (readDelayMillis > 0) Thread.sleep(readDelayMillis)
        return values[key] ?: defValue
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun getInt(key: String, defValue: Int): Int = defValue

    override fun getLong(key: String, defValue: Long): Long = defValue

    override fun getFloat(key: String, defValue: Float): Float = defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    /** Batches edits and applies them at once, as the real editor does. */
    private inner class FakeEditor : SharedPreferences.Editor {

        private val puts = LinkedHashMap<String, String>()
        private val removals = LinkedHashSet<String>()
        private var cleared = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) removals += key else puts[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = this

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            if (cleared) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(puts)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
