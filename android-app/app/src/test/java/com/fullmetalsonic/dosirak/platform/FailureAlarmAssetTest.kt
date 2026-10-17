package com.fullmetalsonic.dosirak.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

class FailureAlarmAssetTest {
    @Test fun bundledPcmHasBoundedPeakDurationAndQuietLoopEdges() {
        val bytes = File("src/main/res/raw/failure_alarm.wav").readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals(1, data.getShort(20).toInt())
        assertEquals(1, data.getShort(22).toInt())
        assertEquals(48_000, data.getInt(24))
        assertEquals(16, data.getShort(34).toInt())
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(bytes.size - 44, data.getInt(40))
        val samples = IntArray((bytes.size - 44) / 2) { data.getShort(44 + it * 2).toInt() }
        assertEquals(1.2, samples.size / 48_000.0, 0.00001)
        assertFalse(samples.any { it == 32767 || it == -32768 })
        val peakDbFs = 20 * log10(samples.maxOf { abs(it) } / 32768.0)
        assertTrue(peakDbFs in -3.5..-3.0)
        assertEquals(0, samples.first())
        assertTrue(samples.takeLast(12_000).all { it == 0 })
        fun positiveCrossings(from: Int, to: Int) = (from + 1 until to).count { samples[it - 1] <= 0 && samples[it] > 0 }
        assertEquals(352.0, positiveCrossings(0, 19_200).toDouble(), 1.0)
        assertEquals(470.0, positiveCrossings(24_000, 43_200).toDouble(), 1.0)
    }
}
