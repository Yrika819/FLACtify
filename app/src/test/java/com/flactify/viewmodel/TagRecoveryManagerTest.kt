package com.flactify.viewmodel

import android.content.Context

import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class TagRecoveryManagerTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var manager: TagRecoveryManager

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "recovery-test-${System.nanoTime()}").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.noBackupFilesDir } returns File(root, "no-backup")
        every { context.filesDir } returns File(root, "files")
        manager = TagRecoveryManager(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `malformed metadata and orphan audio are enumerated defensively`() {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        File(directory, "recovery_malformed.flac").writeBytes(byteArrayOf(1))
        File(directory, "recovery_malformed.flac.json").writeText("{")
        File(directory, "recovery_orphan.flac").writeBytes(byteArrayOf(2))
        val entries = manager.entries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { !it.metadataValid })
    }

    @Test
    fun `orphan metadata is enumerable and deletable`() {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        val metadata = File(directory, "recovery_orphan.flac.json").apply { writeText("{}") }
        val entry = manager.entries().single()
        assertEquals(null, entry.audioFile)
        assertTrue(manager.delete(entry))
        assertFalse(metadata.exists())
    }

    @Test
    fun `delete removes audio and corresponding metadata`() {
        val entry = createEntry("recovery_pair.flac")
        assertTrue(manager.delete(entry))
        assertFalse(entry.audioFile!!.exists())
        assertFalse(entry.metadataFile!!.exists())
    }

    @Test
    fun `export copies full recovery file and leaves source intact`() {
        val entry = createEntry("recovery_export.flac")
        val output = ByteArrayOutputStream()
        manager.export(entry) { output }
        assertTrue(entry.audioFile!!.readBytes().contentEquals(output.toByteArray()))
        assertTrue(entry.audioFile!!.exists())
    }

    @Test
    fun `export failure preserves audio and metadata`() {
        val entry = createEntry("recovery_failed.flac")
        val failure = runCatching { manager.export(entry) { null } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(entry.audioFile!!.exists())
        assertTrue(entry.metadataFile!!.exists())
    }

    @Test
    fun `capacity limit requires managing entries and retains existing copies`() {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        repeat(3) { File(directory, "recovery_$it.flac").writeBytes(byteArrayOf(it.toByte())) }
        val failure = runCatching { TagRecoveryManager.ensureCapacity(context, 3) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("Export or delete"))
        assertEquals(3, directory.listFiles()?.count { !it.name.endsWith(".json") })
    }

    private fun createEntry(name: String): TagRecoveryManager.Entry {
        val directory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
        val audio = File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val sidecar = File(directory, "$name.json").apply { writeText("{") }
        return TagRecoveryManager.Entry("id", "unknown", null, "invalid metadata", audio, sidecar, false)
    }
}
