package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.audio.Wav16
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Wav16Test {
    @Test fun interruptedWaveIsRepairedWithoutChangingPcm() {
        val file = File.createTempFile("scribe-wave", ".wav")
        try {
            val pcm = ByteArray(32000) { (it % 127).toByte() }
            RandomAccessFile(file, "rw").use { Wav16.header(it, 0); it.write(pcm) }
            assertEquals(1000L, Wav16.repair(file))
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals(16000, header.getInt(24))
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(32000, header.getInt(40))
            assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
        } finally { file.delete() }
    }
    @Test fun partialSampleAtProcessDeathIsDiscarded() {
        val file = File.createTempFile("scribe-wave", ".wav")
        try {
            RandomAccessFile(file, "rw").use { Wav16.header(it, 0); it.write(byteArrayOf(1, 2, 3)) }
            Wav16.repair(file)
            assertEquals(46L, file.length())
        } finally { file.delete() }
    }
}
