package com.example.smartrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class AudioMonitorService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_UPDATE_AMPLITUDE = "ACTION_UPDATE_AMPLITUDE"
        const val EXTRA_AMPLITUDE = "EXTRA_AMPLITUDE"
        const val EXTRA_THRESHOLD = "EXTRA_THRESHOLD"

        private const val CHANNEL_ID = "AudioMonitorChannel"
        private const val NOTIFICATION_ID = 1

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isMonitoring = false
    private var threshold = 500

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var timerJob: Job? = null

    // State machine
    private enum class State { IDLE, RECORDING, SILENCE_COUNTDOWN }
    private var currentState = State.IDLE

    private var rawFile: File? = null
    private var outputStream: FileOutputStream? = null
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        threshold = intent?.getIntExtra(EXTRA_THRESHOLD, 500) ?: 500

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isMonitoring) {
            startMonitoring()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val stopIntent = Intent(this, AudioMonitorService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Recorder Active")
            .setContentText("Monitoring audio levels...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Default system icon
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startMonitoring() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioMonitorService", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isMonitoring = true
            currentState = State.IDLE

            serviceScope.launch {
                val buffer = ShortArray(bufferSize / 2)
                while (isMonitoring) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        processAudio(buffer, readResult)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("AudioMonitorService", "Permission not granted: ${e.message}")
        }
    }

    private suspend fun processAudio(buffer: ShortArray, length: Int) {
        var maxAmplitude = 0
        for (i in 0 until length) {
            val absValue = abs(buffer[i].toInt())
            if (absValue > maxAmplitude) {
                maxAmplitude = absValue
            }
        }

        // Broadcast amplitude for UI
        val intent = Intent(ACTION_UPDATE_AMPLITUDE)
        intent.putExtra(EXTRA_AMPLITUDE, maxAmplitude)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        withContext(Dispatchers.IO) {
            when (currentState) {
                State.IDLE -> {
                    if (maxAmplitude >= threshold) {
                        // Start recording
                        startRecordingToFile()
                        writeAudio(buffer, length)
                        currentState = State.RECORDING
                    }
                }
                State.RECORDING -> {
                    writeAudio(buffer, length)
                    if (maxAmplitude < threshold) {
                        // Enter silence countdown
                        currentState = State.SILENCE_COUNTDOWN
                        startSilenceTimer()
                    }
                }
                State.SILENCE_COUNTDOWN -> {
                    writeAudio(buffer, length)
                    if (maxAmplitude >= threshold) {
                        // Interrupt and cancel timer
                        timerJob?.cancel()
                        currentState = State.RECORDING
                    }
                }
            }
        }
    }

    private fun startRecordingToFile() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        rawFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "RECORD_$timeStamp.pcm")
        try {
            outputStream = FileOutputStream(rawFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeAudio(buffer: ShortArray, length: Int) {
        try {
            val byteBuffer = ByteBuffer.allocate(length * 2)
            byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(buffer, 0, length)
            outputStream?.write(byteBuffer.array())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSilenceTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            delay(20000L) // 20 seconds
            // If delay completes without cancellation, stop and save
            withContext(Dispatchers.IO) {
                stopRecordingAndSave()
                currentState = State.IDLE
            }
        }
    }

    private fun stopRecordingAndSave() {
        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null

            rawFile?.let { pcm ->
                val wavFile = File(pcm.absolutePath.replace(".pcm", ".wav"))
                WavFileHelper.saveWavFile(pcm, wavFile, SAMPLE_RATE, 1, 16)
                // Delete raw pcm if desired
                pcm.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        serviceScope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopRecordingAndSave()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
