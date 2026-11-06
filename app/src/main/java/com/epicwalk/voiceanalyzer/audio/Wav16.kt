package com.epicwalk.voiceanalyzer.audio

import java.io.File
import java.io.RandomAccessFile

/** PCM16 mono 16 kHz. Times refer to saved samples; pauses do not add samples. */
object Wav16 {
    const val SAMPLE_RATE = 16000
    const val HEADER_SIZE = 44L
    fun header(file: RandomAccessFile, sampleCount: Long) {
        require(sampleCount in 0..(0xffffffffL - 36) / 2)
        val position = file.filePointer
        fun u32(value: Long) = file.writeInt(Integer.reverseBytes(value.toInt()))
        fun u16(value: Int) = file.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
        file.seek(0)
        file.writeBytes("RIFF"); u32(sampleCount * 2 + 36); file.writeBytes("WAVEfmt ")
        u32(16); u16(1); u16(1); u32(SAMPLE_RATE.toLong()); u32(SAMPLE_RATE * 2L)
        u16(2); u16(16); file.writeBytes("data"); u32(sampleCount * 2)
        file.seek(maxOf(HEADER_SIZE, position))
    }
    fun repair(file: File): Long = RandomAccessFile(file, "rw").use {
        require(it.length() >= HEADER_SIZE) { "Неполный WAV-файл" }
        val samples = (it.length() - HEADER_SIZE) / 2
        it.setLength(HEADER_SIZE + samples * 2)
        header(it, samples)
        samples * 1000 / SAMPLE_RATE
    }
}
