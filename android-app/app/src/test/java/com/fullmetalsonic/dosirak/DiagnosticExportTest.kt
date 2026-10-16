package com.fullmetalsonic.dosirak

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class DiagnosticExportTest {
    private val payload = byteArrayOf(0x50, 0x4b, 3, 4, 0, 0, 0x7f, -1)

    @Test fun successfulBinaryCopyPreservesAllBytesAndDoesNotDelete() = runBlocking {
        val output = ByteArrayOutputStream()
        var deletions = 0
        val result = copyDiagnosticExport({ ByteArrayInputStream(payload) }, { output }, { deletions++; true })
        assertEquals(DiagnosticExportResult.SAVED, result)
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(0, deletions)
    }

    @Test fun partialWriteFailureDeletesCreatedDocument() = runBlocking {
        var deletions = 0
        val result = copyDiagnosticExport({ ByteArrayInputStream(payload) }, { failingOutput() }, { deletions++; true })
        assertEquals(DiagnosticExportResult.FAILED, result)
        assertEquals(1, deletions)
    }

    @Test fun closeFailureAlsoDeletesCreatedDocument() = runBlocking {
        var deletions = 0
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("close failed") }
        }
        val result = copyDiagnosticExport({ ByteArrayInputStream(payload) }, { output }, { deletions++; true })
        assertEquals(DiagnosticExportResult.FAILED, result)
        assertEquals(1, deletions)
    }

    @Test fun failedOrThrowingCleanupReportsIncompleteFile() = runBlocking {
        for (throws in listOf(false, true)) {
            val result = copyDiagnosticExport({ ByteArrayInputStream(payload) }, { failingOutput() }, {
                if (throws) throw IOException("delete failed")
                false
            })
            assertEquals(DiagnosticExportResult.INCOMPLETE, result)
        }
    }

    @Test fun unavailableOrInvalidatedSourceDoesNotOpenOrDeleteDestination() = runBlocking {
        var opens = 0
        var deletions = 0
        assertEquals(DiagnosticExportResult.UNAVAILABLE, copyDiagnosticExport({ null },
            { opens++; ByteArrayOutputStream() }, { deletions++; true }))
        assertEquals(DiagnosticExportResult.FAILED, copyDiagnosticExport({ throw IOException("source missing") },
            { opens++; ByteArrayOutputStream() }, { deletions++; true }))
        assertEquals(0, opens)
        assertEquals(0, deletions)
    }

    @Test fun failedDestinationOpenDoesNotAttemptDeletion() = runBlocking {
        var deletions = 0
        assertEquals(DiagnosticExportResult.FAILED, copyDiagnosticExport({ ByteArrayInputStream(payload) },
            { throw IOException("open failed") }, { deletions++; true }))
        assertEquals(0, deletions)
    }

    @Test fun onlyNewSafDocumentsCanBeDeletedAfterWriteFailure() = runBlocking {
        for ((created, document) in listOf(false to false, false to true, true to false, true to true)) {
            var deletions = 0
            val result = copyDiagnosticExport({ ByteArrayInputStream(payload) }, { failingOutput() }, {
                discardCreatedDiagnosticDocument(created, { document }, { deletions++; true })
            })
            assertEquals(if (created && document) 1 else 0, deletions)
            assertEquals(if (created && document) DiagnosticExportResult.FAILED else DiagnosticExportResult.INCOMPLETE, result)
        }
    }

    @Test fun cancellationAfterPartialWriteCleansUpAndRethrowsEvenInCancelledContext() = runBlocking {
        for (cleanupSucceeds in listOf(true, false)) {
            val job = Job()
            var deletions = 0
            var reportedIncomplete: Boolean? = null
            try {
                withContext(job) {
                    copyDiagnosticExport({ ByteArrayInputStream(payload) }, {
                        object : OutputStream() {
                            override fun write(value: Int) { job.cancel() }
                        }
                    }, { deletions++; cleanupSucceeds }, { reportedIncomplete = it })
                }
                fail("Cancellation must escape after cleanup")
            } catch (_: CancellationException) {
                assertEquals(1, deletions)
                assertEquals(!cleanupSucceeds, reportedIncomplete)
            }
        }
    }

    private fun failingOutput(): OutputStream = object : ByteArrayOutputStream() {
        override fun write(value: Int) { super.write(value); throw IOException("partial write failed") }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > 0) super.write(buffer, offset, 1)
            throw IOException("partial write failed")
        }
    }
}
