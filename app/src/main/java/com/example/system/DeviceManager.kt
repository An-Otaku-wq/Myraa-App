package com.example.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.widget.Toast

class DeviceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun adjustVolume(percent: Int) {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val ratio = percent.coerceIn(0, 100).toDouble() / 100.0
            val targetVolume = (ratio * maxVolume).toInt()
            val finalVolume = targetVolume.coerceIn(0, maxVolume)
            
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                finalVolume,
                AudioManager.FLAG_SHOW_UI
            )
            showToast("Volume adjusted to $percent% ($finalVolume/$maxVolume)")
        } catch (e: Exception) {
            Log.e("DeviceManager", "Failed to adjust volume", e)
            showToast("Device error adjusting volume.")
        }
    }

    fun adjustBrightness(activity: Activity?, percent: Int) {
        if (activity == null) return
        activity.runOnUiThread {
            try {
                val layoutParams = activity.window.attributes
                val brightnessValue = (percent / 100f).coerceIn(0.01f, 1.0f)
                layoutParams.screenBrightness = brightnessValue
                activity.window.attributes = layoutParams
                showToast("HUD Brightness set to $percent%")
            } catch (e: Exception) {
                Log.e("DeviceManager", "Failed to adjust brightness", e)
            }
        }
    }

    fun toggleFlashlight(state: String) {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                val turnOn = state.lowercase().trim() == "on"
                cameraManager.setTorchMode(cameraId, turnOn)
                showToast("System Flashlight: ${if (turnOn) "ON" else "OFF"}")
            } else {
                showToast("Flashlight hardware not found")
            }
        } catch (e: Exception) {
            Log.e("DeviceManager", "Failed to control flashlight", e)
            showToast("Torch error: Permission or Hardware busy.")
        }
    }

    fun webSearch(query: String) {
        try {
            val encodedQuery = Uri.encode(query)
            val searchUri = Uri.parse("https://www.google.com/search?q=$encodedQuery")
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            showToast("Opening web search for '$query'...")
        } catch (e: Exception) {
            Log.e("DeviceManager", "Failed to open web search", e)
            showToast("Browser failed to initialize.")
        }
    }

    fun launchAppByAlias(appAlias: String) {
        val packageName = when (appAlias.lowercase().trim()) {
            "youtube", "yt" -> "com.google.android.youtube"
            "chrome", "browser" -> "com.android.chrome"
            "maps", "navigation" -> "com.google.android.apps.maps"
            "gmail", "mail" -> "com.google.android.gm"
            "playstore", "play" -> "com.android.vending"
            "settings" -> "com.android.settings"
            else -> null
        }

        if (packageName != null) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    showToast("Launching $appAlias...")
                } else {
                    // Try launching standard intent or search play store
                    launchStoreOrMockSearch(packageName, appAlias)
                }
            } catch (e: Exception) {
                launchStoreOrMockSearch(packageName, appAlias)
            }
        } else {
            // General implicit intent mapping
            showToast("No direct package registered for: $appAlias")
        }
    }

    private fun launchStoreOrMockSearch(packageName: String, appName: String) {
        try {
            val marketUri = Uri.parse("market://details?id=$packageName")
            val intent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            showToast("$appName not installed. Accessing Play Store...")
        } catch (e: Exception) {
            // fallback: perform a web search about the app
            webSearch("download $appName app for android")
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
