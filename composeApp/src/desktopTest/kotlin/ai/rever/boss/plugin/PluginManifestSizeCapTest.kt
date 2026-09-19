package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginManifestException
import ai.rever.boss.plugin.loader.PluginManifestReader
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManifestSizeCapTest {
    private val tempDir =
        File(
            System.getProperty("user.home"),
            "boss-manifest-cap-test-${System.currentTimeMillis()}",
        )

    @BeforeTest
    fun setUp() {
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createJarWithManifest(manifestContent: String): File {
        val jarFile = File(tempDir, "test-plugin-${System.currentTimeMillis()}.jar")
        JarOutputStream(FileOutputStream(jarFile)).use { jos ->
            val entry = JarEntry("META-INF/boss-plugin/plugin.json")
            jos.putNextEntry(entry)
            jos.write(manifestContent.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarFile
    }

    @Test
    fun testPluginManifestReaderEnforcesSizeCapOnOversizedManifest() {
        // Create an oversized JSON manifest exceeding 512 KB
        val padding = " ".repeat(600 * 1024)
        val oversizedJson = """{"id": "test", "name": "Test", "version": "1.0.0", "padding": "$padding"}"""
        val jarFile = createJarWithManifest(oversizedJson)

        val exception =
            assertFailsWith<PluginManifestException> {
                PluginManifestReader.readFromJar(jarFile.absolutePath)
            }
        assertTrue(exception.message?.contains("exceeds maximum allowed size") == true)
    }

    @Test
    fun testValidManifestIsReadSuccessfully() {
        val validJson =
            """{"pluginId": "com.example.test", "displayName": "Test", "version": "1.0.0", "apiVersion": "1.0.0", "mainClass": "com.example.Test"}"""
        val jarFile = createJarWithManifest(validJson)

        val manifest = PluginManifestReader.readFromJar(jarFile.absolutePath)
        assertTrue(manifest.pluginId == "com.example.test")
        assertTrue(manifest.version == "1.0.0")
    }
}
