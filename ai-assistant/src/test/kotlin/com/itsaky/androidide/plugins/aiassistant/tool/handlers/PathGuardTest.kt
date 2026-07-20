package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [PathGuard] — the shared containment guard for filesystem
 * tools.
 *
 * These cover the regression that made `open_file .gitignore` fail with
 * "outside the project directory": the guard was anchored to `user.dir` ("/" on
 * Android) instead of the open project, so every relative path was rejected.
 */
class PathGuardTest {

    private lateinit var projectRoot: File

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("pathguard-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
    }

    @After
    fun tearDown() {
        // Reset shared singleton state so tests don't leak into each other.
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    // --- The reported bug -----------------------------------------------------

    @Test
    fun givenARelativeDotfile_whenResolveWithinIsCalled_thenItResolvesInsideTheProjectRoot() {
        val resolved = PathGuard.resolveWithin(".gitignore")

        assertNotNull("'.gitignore' must resolve inside the project root", resolved)
        assertEquals(File(projectRoot, ".gitignore").canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun givenTheFilesystemRootAsProjectRoot_whenARelativePathIsResolved_thenItIsNotRejected() {
        // Reproduces the exact failure mode: with root "/", the old
        // `root + File.separator` produced "//" and rejected everything.
        PathGuard.setProjectRootForTesting("/")

        val resolved = PathGuard.resolveWithin(".gitignore")

        assertNotNull("A relative path under '/' must not self-reject", resolved)
        assertEquals(File("/.gitignore").canonicalPath, resolved!!.canonicalPath)
    }

    // --- Normal containment ---------------------------------------------------

    @Test
    fun givenANestedRelativePath_whenResolveWithinIsCalled_thenItResolvesInsideTheProjectRoot() {
        val resolved = PathGuard.resolveWithin("app/src/main/AndroidManifest.xml")

        assertNotNull(resolved)
        assertEquals(
            File(projectRoot, "app/src/main/AndroidManifest.xml").canonicalPath,
            resolved!!.canonicalPath
        )
    }

    @Test
    fun givenTheProjectRootPath_whenResolveWithinIsCalled_thenItResolves() {
        val resolved = PathGuard.resolveWithin(".")

        assertNotNull(resolved)
        assertEquals(projectRoot.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun givenAnAbsolutePathInsideTheRoot_whenResolveWithinIsCalled_thenItResolves() {
        val inside = File(projectRoot, "build.gradle.kts").absolutePath

        val resolved = PathGuard.resolveWithin(inside)

        assertNotNull(resolved)
        assertEquals(File(inside).canonicalPath, resolved!!.canonicalPath)
    }

    // --- Escape attempts are rejected ----------------------------------------

    @Test
    fun givenARelativeTraversalEscapingTheRoot_whenResolveWithinIsCalled_thenItIsRejected() {
        assertNull(PathGuard.resolveWithin("../secrets.txt"))
    }

    @Test
    fun givenAnAbsolutePathOutsideTheRoot_whenResolveWithinIsCalled_thenItIsRejected() {
        assertNull(PathGuard.resolveWithin("/etc/passwd"))
    }

    @Test
    fun givenASiblingDirectorySharingANamePrefix_whenResolveWithinIsCalled_thenItIsRejected() {
        // e.g. root "/tmp/proj" must NOT accept "/tmp/proj-evil".
        assertNull(PathGuard.resolveWithin(projectRoot.absolutePath + "-evil/file.txt"))
    }

    // --- Root resolution precedence ------------------------------------------

    @Test
    fun givenNoTestOverride_whenTheProjectRootIsQueried_thenTheProviderSuppliesIt() {
        PathGuard.setProjectRootForTesting(null)
        val providerRoot = Files.createTempDirectory("pathguard-provider").toFile().canonicalFile
        try {
            PathGuard.setProjectRootProvider { providerRoot.absolutePath }

            assertEquals(providerRoot.canonicalPath, File(PathGuard.projectRoot()).canonicalPath)
            assertNotNull(PathGuard.resolveWithin("settings.gradle"))
        } finally {
            providerRoot.deleteRecursively()
        }
    }

    // --- findByName (basename resolution) ------------------------------------

    @Test
    fun givenANestedFile_whenFindByNameIsCalledWithItsBasename_thenItIsLocated() {
        File(projectRoot, "app/src/main/java/com/example").mkdirs()
        File(projectRoot, "app/src/main/java/com/example/MainActivity.java").writeText("x")

        val found = PathGuard.findByName("MainActivity.java")

        assertEquals(1, found.size)
        assertEquals(
            File(projectRoot, "app/src/main/java/com/example/MainActivity.java").canonicalPath,
            found[0].canonicalPath
        )
    }

    @Test
    fun givenFilesDifferingOnlyInCase_whenFindByNameIsCalled_thenItMatchesAllCaseInsensitively() {
        File(projectRoot, "a").mkdirs(); File(projectRoot, "a/Notes.txt").writeText("x")
        File(projectRoot, "b").mkdirs(); File(projectRoot, "b/notes.txt").writeText("x")

        assertEquals(2, PathGuard.findByName("notes.txt").size)
    }

    @Test
    fun givenFilesUnderBuildAndDotDirectories_whenFindByNameIsCalled_thenTheyAreSkipped() {
        File(projectRoot, "build/generated").mkdirs()
        File(projectRoot, "build/generated/R.java").writeText("x")
        File(projectRoot, ".git").mkdirs()
        File(projectRoot, ".git/R.java").writeText("x")

        assertTrue("must not match files under build/ or .git/", PathGuard.findByName("R.java").isEmpty())
    }

    @Test
    fun givenNoMatchingFile_whenFindByNameIsCalled_thenItReturnsEmpty() {
        assertTrue(PathGuard.findByName("DoesNotExist.kt").isEmpty())
    }

    // --- resolve() — the shared handler resolution policy --------------------

    @Test
    fun givenAnExistingRelativePath_whenResolveIsCalled_thenItReturnsResolved() {
        File(projectRoot, "app").mkdirs()
        val target = File(projectRoot, "app/build.gradle.kts").apply { writeText("x") }

        val resolution = PathGuard.resolve("app/build.gradle.kts")

        assertTrue(resolution is PathGuard.Resolution.Resolved)
        assertEquals(target.canonicalPath, (resolution as PathGuard.Resolution.Resolved).file.canonicalPath)
    }

    @Test
    fun givenASlashPrefixedPath_whenResolveIsCalled_thenItRetriesAsRelativeBeforeBasenameSearch() {
        // Two matching basenames would make a basename search ambiguous; the
        // slash-stripped relative retry must resolve the exact one first.
        File(projectRoot, "app").mkdirs(); File(projectRoot, "app/.gitignore").writeText("x")
        File(projectRoot, ".gitignore").writeText("x")

        val resolution = PathGuard.resolve("/app/.gitignore")

        assertTrue(resolution is PathGuard.Resolution.Resolved)
        assertEquals(
            File(projectRoot, "app/.gitignore").canonicalPath,
            (resolution as PathGuard.Resolution.Resolved).file.canonicalPath
        )
    }

    @Test
    fun givenABareName_whenResolveIsCalled_thenItReturnsResolvedViaBasenameSearch() {
        File(projectRoot, "app/src/main").mkdirs()
        File(projectRoot, "app/src/main/MainActivity.java").writeText("x")

        val resolution = PathGuard.resolve("MainActivity.java")

        assertTrue(resolution is PathGuard.Resolution.Resolved)
    }

    @Test
    fun givenABasenameMatchingSeveralFiles_whenResolveIsCalled_thenItReturnsAmbiguous() {
        File(projectRoot, "a").mkdirs(); File(projectRoot, "a/Strings.kt").writeText("x")
        File(projectRoot, "b").mkdirs(); File(projectRoot, "b/Strings.kt").writeText("x")

        val resolution = PathGuard.resolve("Strings.kt")

        assertTrue(resolution is PathGuard.Resolution.Ambiguous)
        assertEquals("Strings.kt", (resolution as PathGuard.Resolution.Ambiguous).baseName)
        assertEquals(2, resolution.matches.size)
    }

    @Test
    fun givenAPathWithNoInRootInterpretation_whenResolveIsCalled_thenItReturnsEscaped() {
        assertEquals(PathGuard.Resolution.Escaped, PathGuard.resolve("../outside.txt"))
    }

    @Test
    fun givenASlashPrefixedInRootPathThatDoesNotExist_whenResolveIsCalled_thenItReturnsNotFound() {
        // "/does/exist.kt" resolves outside as absolute, but its relative reading
        // is in-root — so a miss is NotFound, not Escaped.
        assertEquals(PathGuard.Resolution.NotFound, PathGuard.resolve("/does/exist.kt"))
    }

    @Test
    fun givenAnInRootPathThatDoesNotExist_whenResolveIsCalled_thenItReturnsNotFound() {
        assertEquals(PathGuard.Resolution.NotFound, PathGuard.resolve("does/not/exist.kt"))
    }

    @Test
    fun givenABlankProviderResult_whenTheProjectRootIsQueried_thenItFallsThroughToTheNextSource() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider { "  " }
        System.setProperty("project.dir", projectRoot.absolutePath)
        try {
            assertEquals(projectRoot.canonicalPath, File(PathGuard.projectRoot()).canonicalPath)
        } finally {
            System.clearProperty("project.dir")
        }
    }
}
