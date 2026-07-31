package org.appdevforall.projecttotemplate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for the plugin-API-free conversion logic. These exercise only
 * the substitution/sanitize paths (and the dry-run pipeline), which touch no
 * Android APIs, so they run under `unitTests.isReturnDefaultValues = true`
 * without hitting stubbed android.jar classes.
 */
class TemplatizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `token wraps the name in Pebble delimiters`() {
        assertEquals("\${{APP_NAME}}", token("APP_NAME"))
    }

    @Test
    fun `sanitizeTemplateName maps disallowed characters to underscore`() {
        assertEquals("My_Template_", sanitizeTemplateName("  My Template!  "))
    }

    @Test
    fun `sanitizeTemplateName strips path traversal so the name can never escape the output dir`() {
        val safe = sanitizeTemplateName("../../etc/passwd")
        assertFalse(safe.contains("/"))
        assertFalse(safe.contains(File.separator))
        assertFalse(safe.startsWith("."))
        assertFalse(safe == ".." || safe == ".")
    }

    @Test
    fun `sanitizeTemplateName falls back to template when nothing usable remains`() {
        assertEquals("template", sanitizeTemplateName("   "))
        assertEquals("template", sanitizeTemplateName("..."))
    }

    @Test
    fun `dry run reports the expected substitutions without writing output`() {
        val project = tmp.newFolder("MyApp")
        File(project, "settings.gradle.kts").writeText(
            """rootProject.name = "MyApp"${"\n"}"""
        )
        val app = File(project, "app").apply { mkdirs() }
        File(app, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                kotlin("android") version "2.0.0"
            }
            android {
                namespace = "com.example.myapp"
                compileSdk = 34
                defaultConfig {
                    applicationId = "com.example.myapp"
                    minSdk = 26
                    targetSdk = 34
                }
            }
            """.trimIndent()
        )
        val res = File(app, "src/main/res/values").apply { mkdirs() }
        File(res, "strings.xml").writeText(
            """<resources><string name="app_name">MyApp</string></resources>${"\n"}"""
        )

        val result = createTemplateBundle(
            projectDir = project,
            module = "app",
            templateName = "My Template!",
            dryRun = true,
        )

        // Dry run produces no .cgt and leaves the real output directory untouched.
        assertNull(result.cgtFile)
        assertNull(result.projectBundleDir)
        assertFalse(File(project.parentFile, "${project.name}-cgt").exists())

        // The recognized files were tokenized into .peb previews.
        assertTrue(
            "expected settings.gradle.kts to be templatized: ${result.report.changed}",
            result.report.changed.any { it.contains("settings.gradle.kts.peb") },
        )
        assertTrue(
            "expected strings.xml to be templatized: ${result.report.changed}",
            result.report.changed.any { it.contains("strings.xml.peb") },
        )
        assertTrue(
            "expected app/build.gradle.kts to be templatized: ${result.report.changed}",
            result.report.changed.any { it.contains("build.gradle.kts.peb") },
        )
    }
}
