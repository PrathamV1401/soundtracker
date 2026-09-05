package com.example.smartrecorder

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var tvAmplitude: TextView
    private lateinit var tvThresholdLabel: TextView
    private lateinit var sbThreshold: SeekBar
    private lateinit var btnToggleService: Button

    private var currentThreshold = 500

    private val permissionRequestCode = 101

    private val amplitudeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val amplitude = intent?.getIntExtra(AudioMonitorService.EXTRA_AMPLITUDE, 0) ?: 0
            tvAmplitude.text = amplitude.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAmplitude = findViewById(R.id.tvAmplitude)
        tvThresholdLabel = findViewById(R.id.tvThresholdLabel)
        sbThreshold = findViewById(R.id.sbThreshold)
        btnToggleService = findViewById(R.id.btnToggleService)

        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = progress
                tvThresholdLabel.text = "Recording Threshold: $currentThreshold"
                
                // If service is running, update its threshold
                if (isServiceRunning(AudioMonitorService::class.java)) {
                    val intent = Intent(this@MainActivity, AudioMonitorService::class.java)
                    intent.putExtra(AudioMonitorService.EXTRA_THRESHOLD, currentThreshold)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateButtonState()

        btnToggleService.setOnClickListener {
            if (isServiceRunning(AudioMonitorService::class.java)) {
                stopAudioService()
            } else {
                if (checkPermissions()) {
                    startAudioService()
                } else {
                    requestPermissions()
                }
            }
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            amplitudeReceiver,
            IntentFilter(AudioMonitorService.ACTION_UPDATE_AMPLITUDE)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(amplitudeReceiver)
    }

    private fun startAudioService() {
        val intent = Intent(this, AudioMonitorService::class.java)
        intent.putExtra(AudioMonitorService.EXTRA_THRESHOLD, currentThreshold)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateButtonState()
    }

    private fun stopAudioService() {
        val intent = Intent(this, AudioMonitorService::class.java)
        intent.action = AudioMonitorService.ACTION_STOP_SERVICE
        startService(intent) // Send the stop action
        updateButtonState()
    }

    private fun updateButtonState() {
        btnToggleService.postDelayed({
            if (isServiceRunning(AudioMonitorService::class.java)) {
                btnToggleService.text = "Stop Service"
            } else {
                btnToggleService.text = "Start Service"
                tvAmplitude.text = "0"
            }
        }, 200) // Slight delay to allow service state to update
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun checkPermissions(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        
        var permissionsGranted = recordAudio == PackageManager.PERMISSION_GRANTED
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifications = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            permissionsGranted = permissionsGranted && notifications == PackageManager.PERMISSION_GRANTED
        }
        return permissionsGranted
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            permissionRequestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAudioService()
            } else {
                Toast.makeText(this, "Permissions required for recording", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
