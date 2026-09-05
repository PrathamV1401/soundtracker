package com.example.smartrecorder

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFileHelper {

    private const val HEADER_SIZE = 44

    fun saveWavFile(rawPcmFile: File, wavFile: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val pcmLength = rawPcmFile.length()
        val dataSize = pcmLength.toInt()

        try {
            FileInputStream(rawPcmFile).use { fis ->
                FileOutputStream(wavFile).use { fos ->
                    val header = createWavHeader(dataSize, sampleRate, channels, bitsPerSample)
                    fos.write(header)
                    
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val buffer = ByteBuffer.wrap(header)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        buffer.put("RIFF".toByteArray())
        buffer.putInt(dataSize + 36)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1.toShort()) // AudioFormat (PCM)
        buffer.putShort(channels.toShort()) // NumChannels
        buffer.putInt(sampleRate) // SampleRate
        buffer.putInt(byteRate) // ByteRate
        buffer.putShort(blockAlign.toShort()) // BlockAlign
        buffer.putShort(bitsPerSample.toShort()) // BitsPerSample

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        return header
    }
}
