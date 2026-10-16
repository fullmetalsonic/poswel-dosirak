package com.fullmetalsonic.dosirak.diagnostics

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiagnosticSharingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test fun zipUsesArchiveMimeAndTemporaryReadAccess() {
        val directory = File(context.cacheDir, "analysis-share").apply { mkdirs() }
        val file = File(directory, "share-test.zip")
        try {
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("summary.txt")); zip.write("safe".toByteArray()); zip.closeEntry()
            }
            val chooser = DiagnosticSharing.chooser(context, file)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("application/zip", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val uri = send.clipData!!.getItemAt(0).uri
            context.contentResolver.openInputStream(uri)!!.use { assertEquals(0x50, it.read()); assertEquals(0x4b, it.read()) }
        } finally { file.delete() }
    }

    @Suppress("DEPRECATION")
    @Test fun chooserSharesOnlyTheSnapshotWithTemporaryReadAccess() {
        val directory = File(context.cacheDir, "analysis-share").apply { mkdirs() }
        val file = File(directory, "share-test.txt")
        val payload = "POSWEL ANALYSIS schema=1 version=1.0.3\n{\"event\":\"RUN_FINISHED\",\"result\":\"FAILED\"}\n"
        try {
            file.writeText(payload)
            val chooser = DiagnosticSharing.chooser(context, file)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(0, send.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val uri = send.clipData!!.getItemAt(0).uri
            assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("${context.packageName}.analysis-files", uri.authority)
            assertEquals(payload, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
            assertFalse(send.hasExtra(Intent.EXTRA_EMAIL))
        } finally { file.delete() }
    }

    @Test fun providerAndShareHelperRejectPrivateAndSiblingCacheFiles() {
        val siblings = listOf(File(context.cacheDir, "private-share-test.txt"),
            File(context.filesDir, "private-share-test.txt"), File(context.noBackupFilesDir, "private-share-test.txt"))
        try {
            siblings.forEach { file ->
                file.parentFile!!.mkdirs()
                file.writeText("PRIVATE_SENTINEL_DO_NOT_SHARE")
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(context, "${context.packageName}.analysis-files", file)
                }
                assertThrows(IllegalArgumentException::class.java) { DiagnosticSharing.chooser(context, file) }
            }
            val escaped = File(context.cacheDir, "analysis-share/../private-share-test.txt")
            assertThrows(IllegalArgumentException::class.java) { DiagnosticSharing.chooser(context, escaped) }
        } finally { siblings.forEach { it.delete() } }
    }
}
