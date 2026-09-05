package org.appdevforall.templatemanagerplugin.adapters

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.appcompat.view.ContextThemeWrapper
import org.appdevforall.templatemanagerplugin.R
import org.appdevforall.templatemanagerplugin.models.CgtFileItem
import org.appdevforall.templatemanagerplugin.models.TemplateMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * UI test that inflates and binds [CgtFileAdapter]'s card layout on-device, verifying the
 * rendered text and status. Runs standalone (no COGO host needed) because the adapter only
 * depends on androidx + the plugin's own resources.
 */
@RunWith(AndroidJUnit4::class)
class CgtFileAdapterTest {

    private lateinit var parent: ViewGroup

    private fun item(
        name: String,
        installed: Boolean,
        templates: List<TemplateMetadata>
    ) = CgtFileItem(
        file = File("/tmp/$name"),
        name = name,
        templates = templates,
        installed = installed,
        unregisterName = name
    )

    @Before
    fun setUp() {
        // Card layout uses Material3 theme attributes, so inflate under a Material3 theme.
        val themed = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            com.google.android.material.R.style.Theme_Material3_DayNight
        )
        parent = FrameLayout(themed)
    }

    private fun bind(item: CgtFileItem): View {
        val items = listOf(item)
        val adapter = CgtFileAdapter(
            items,
            onInstall = {}, onUninstall = {}, onDetails = {},
            onDelete = {}, onViewTemplates = {}, onLongPress = {}
        )
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.bindViewHolder(holder, 0)
        return holder.itemView
    }

    @Test
    fun installedSingleTemplate_showsMetadataAndInstalledStatus() {
        val view = bind(
            item(
                "core.cgt",
                installed = true,
                templates = listOf(TemplateMetadata("Basic Activity", "Creates a new basic activity", "0.1"))
            )
        )
        assertEquals("Basic Activity", view.findViewById<TextView>(R.id.tvTemplateName).text.toString())
        assertEquals("Creates a new basic activity", view.findViewById<TextView>(R.id.tvTemplateDesc).text.toString())
        assertEquals("core", view.findViewById<TextView>(R.id.tvFileName).text.toString())
        assertEquals("v0.1", view.findViewById<TextView>(R.id.tvTemplateVersion).text.toString())
        assertEquals("Installed", view.findViewById<TextView>(R.id.tvStatus).text.toString())
        assertEquals(View.GONE, view.findViewById<TextView>(R.id.tvMultiTemplate).visibility)
    }

    @Test
    fun notInstalled_showsNotInstalledStatus() {
        val view = bind(
            item(
                "widget.cgt",
                installed = false,
                templates = listOf(TemplateMetadata("Widget", "d", "1.0"))
            )
        )
        assertEquals("Not installed", view.findViewById<TextView>(R.id.tvStatus).text.toString())
    }

    @Test
    fun multiTemplate_showsContainsCount() {
        val view = bind(
            item(
                "core.cgt",
                installed = true,
                templates = listOf(
                    TemplateMetadata("A", "da", "0.1"),
                    TemplateMetadata("B", "db", "0.1"),
                    TemplateMetadata("C", "dc", "0.1")
                )
            )
        )
        val multi = view.findViewById<TextView>(R.id.tvMultiTemplate)
        assertEquals(View.VISIBLE, multi.visibility)
        assertEquals("Contains 3 templates", multi.text.toString())
    }

    @Test
    fun blankVersion_hidesVersionChip() {
        val view = bind(
            item(
                "x.cgt",
                installed = false,
                templates = listOf(TemplateMetadata("X", "d", ""))
            )
        )
        assertEquals(View.GONE, view.findViewById<TextView>(R.id.tvTemplateVersion).visibility)
    }

    @Test
    fun longPress_invokesCallbackWithCardView() {
        val items = listOf(item("x.cgt", false, listOf(TemplateMetadata("X", "d", "1.0"))))
        var pressed: View? = null
        val adapter = CgtFileAdapter(
            items,
            onInstall = {}, onUninstall = {}, onDetails = {},
            onDelete = {}, onViewTemplates = {}, onLongPress = { pressed = it }
        )
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.bindViewHolder(holder, 0)

        assertTrue(holder.itemView.performLongClick())
        assertEquals(holder.itemView, pressed)
    }
}
