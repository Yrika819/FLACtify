package com.flactify.viewmodel


import android.content.Context
import android.content.ContextWrapper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class TagRecoveryManagerDeviceTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(app.cacheDir, "recovery-test-${System.nanoTime()}").apply { mkdirs() }
        context = IsolatedContext(app, root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun recoveryDirectoryIsUnderNoBackupStorage() {
        assertEquals(File(context.noBackupFilesDir, "tag-recovery"), TagRecoveryManager.recoveryDirectory(context))
        assertFalse(TagRecoveryManager.recoveryDirectory(context).path.startsWith(context.filesDir.path))
    }

    @Test
    fun enumeratesValidRecoveryMetadata() {
        validEntry("recovery_valid.flac")
        val entry = TagRecoveryManager(context).entries().single()
        assertTrue(entry.metadataValid)
        assertEquals("content://provider/document/track", entry.originalIdentifier)
        assertEquals(7L, entry.sizeBytes)
        assertEquals("Write or verification failed", entry.failureReason)
    }

    @Test
    fun malformedSidecarAndOrphanAudioRemainManageable() {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        File(directory, "recovery_malformed.flac").writeBytes(byteArrayOf(1))
        File(directory, "recovery_malformed.flac.json").writeText("{")
        File(directory, "recovery_orphan.flac").writeBytes(byteArrayOf(2))
        val entries = TagRecoveryManager(context).entries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { !it.metadataValid })
        assertTrue(entries.any { it.failureReason.contains("metadata") })
    }

    @Test
    fun validLegacyRecoveryPairMigratesAndAppearsExactlyOnce() {
        val legacy = File(context.filesDir, "tag-recovery").apply { mkdirs() }
        val name = "recovery_legacy.flac"
        File(legacy, name).writeBytes(byteArrayOf(1, 2, 3))
        File(legacy, "$name.json").writeText(
            """{"originalUri":"content://provider/document/legacy","createdAt":123456,"reason":"Legacy failure","recoveryFile":"$name"}"""
        )
        val entries = TagRecoveryManager(context).entries()
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertTrue(entry.metadataValid)
        assertEquals("content://provider/document/legacy", entry.originalIdentifier)
        assertTrue(entry.audioFile?.path?.startsWith(TagRecoveryManager.recoveryDirectory(context).path) == true)
        assertFalse(File(legacy, name).exists())
        assertFalse(File(legacy, "$name.json").exists())
        assertEquals(1, TagRecoveryManager(context).entries().size)
    }

    @Test
    fun failedLegacyCopyLeavesPairIntactAndVisible() {
        val legacy = File(context.filesDir, "tag-recovery").apply { mkdirs() }
        val name = "recovery_copy_failure.flac"
        val audio = File(legacy, name).apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val metadata = File(legacy, "$name.json").apply {
            writeText("""{"originalUri":"content://provider/document/failure","createdAt":123456,"reason":"Copy failed","recoveryFile":"$name"}""")
        }
        val manager = TagRecoveryManager(context) { _, _ -> throw IOException("copy failed") }
        val entry = manager.entries().single()
        assertTrue(entry.metadataValid)
        assertEquals(audio, entry.audioFile)
        assertTrue(audio.exists())
        assertTrue(metadata.exists())
    }

    @Test
    fun duplicateNameCollisionPreservesLegacyPairAndDoesNotOverwrite() {
        val legacy = File(context.filesDir, "tag-recovery").apply { mkdirs() }
        val destination = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        val name = "recovery_collision.flac"
        val legacyAudio = File(legacy, name).apply { writeBytes(byteArrayOf(1, 2)) }
        val legacyMetadata = File(legacy, "$name.json").apply {
            writeText("""{"originalUri":"content://provider/document/legacy","createdAt":123456,"reason":"Legacy","recoveryFile":"$name"}""")
        }
        val destinationAudio = File(destination, name).apply { writeBytes(byteArrayOf(9, 8)) }
        File(destination, "$name.json").writeText(
            """{"originalUri":"content://provider/document/current","createdAt":123457,"reason":"Current","recoveryFile":"$name"}"""
        )

        val entries = TagRecoveryManager(context).entries()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.audioFile == legacyAudio && it.metadataFile == legacyMetadata })
        assertTrue(destinationAudio.readBytes().contentEquals(byteArrayOf(9, 8)))
        assertTrue(legacyAudio.exists())
        assertTrue(legacyMetadata.exists())
    }

    @Test
    fun orphanLegacyRecoveryMaterialRemainsVisible() {
        val legacy = File(context.filesDir, "tag-recovery").apply { mkdirs() }
        val orphanAudio = File(legacy, "recovery_orphan.flac").apply { writeBytes(byteArrayOf(3)) }
        val entry = TagRecoveryManager(context).entries().single()
        assertFalse(entry.metadataValid)
        assertEquals(orphanAudio, entry.audioFile)
        assertTrue(orphanAudio.exists())
    }

    @Test
    fun backupRulesExcludeLegacyRecoveryDirectoryOnly() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertRuleExcludes(app.resources.getXml(com.flactify.R.xml.backup_rules))
        assertRuleExcludes(app.resources.getXml(com.flactify.R.xml.data_extraction_rules))
    }

    private fun assertRuleExcludes(parser: XmlPullParser) {
        var found = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                if (parser.getAttributeValue(null, "domain") == "file" && parser.getAttributeValue(
                        null,
                        "path"
                    ) == "tag-recovery/"
                ) found = true
            }
            parser.next()
        }
        assertTrue(found)
    }

    @Test
    fun orphanMetadataCanBeDeletedDefensively() {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        val sidecar = File(directory, "recovery_missing.flac.json").apply { writeText("{}") }
        val manager = TagRecoveryManager(context)
        val entry = manager.entries().single()
        assertEquals(null, entry.audioFile)
        assertTrue(manager.delete(entry))
        assertFalse(sidecar.exists())
    }

    @Test
    fun deleteRemovesRecoveryAudioAndMatchingSidecar() {
        val audio = validEntry("recovery_delete.flac")
        val manager = TagRecoveryManager(context)
        assertTrue(manager.delete(manager.entries().single()))
        assertFalse(audio.exists())
        assertFalse(File(audio.parentFile, "${audio.name}.json").exists())
    }

    @Test
    fun exportCopiesCompleteAudioAndRetainsRecoverySource() {
        val audio = validEntry("recovery_export.flac")
        val output = ByteArrayOutputStream()
        TagRecoveryManager(context).export(TagRecoveryManager(context).entries().single()) { output }
        assertTrue(audio.readBytes().contentEquals(output.toByteArray()))
        assertTrue(audio.exists())
    }

    @Test
    fun failedExportPreservesRecoveryAudioAndMetadata() {
        val audio = validEntry("recovery_failed.flac")
        val entry = TagRecoveryManager(context).entries().single()
        val failure = runCatching {
            TagRecoveryManager(context).export(entry) { null }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(audio.exists())
        assertTrue(entry.metadataFile?.exists() == true)
    }

    @Test
    fun capacityLimitRejectsAnotherCopyWithoutDeletingOldEntries() {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        repeat(3) { File(directory, "recovery_$it.flac").writeBytes(byteArrayOf(it.toByte())) }
        val failure = runCatching { TagRecoveryManager.ensureCapacity(context, 3) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("Export or delete"))
        assertEquals(3, directory.listFiles()?.count { !it.name.endsWith(".json") })
    }

    private fun validEntry(name: String): File {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        val audio = File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7)) }
        File(directory, "$name.json").writeText(
            """{"originalUri":"content://provider/document/track","createdAt":123456,"reason":"Write or verification failed","recoveryFile":"$name"}"""
        )
        return audio
    }

    private class IsolatedContext(
        base: Context,
        private val root: File
    ) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "no-backup")
        override fun getFilesDir(): File = File(root, "files")
        override fun getCacheDir(): File = File(root, "cache")
    }
}
