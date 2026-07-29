package io.github.mds08011.stow

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile: start or stop a recording from the notification shade without
 * opening the app. This is the lowest-friction path to a note in the field â€” pull down,
 * one tap, start talking.
 *
 * When the app is not ready to record (no API key, missing microphone or notification
 * permission) the tile opens MainActivity instead, since none of those can be resolved
 * from the shade.
 */
class RecordingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        setTileState(isRecording())
    }

    override fun onClick() {
        super.onClick()

        if (isRecording()) {
            startService(
                Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
            )
            setTileState(false)
            return
        }

        if (!isReadyToRecord()) {
            openApp()
            return
        }

        val prefs = prefs()
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_API_KEY, prefs.getString("api_key", ""))
            putExtra(RecordingService.EXTRA_JARGON, prefs.getString("api_jargon", ""))
            // Same contract as the in-app path: the UI copies once the user has chosen.
            putExtra(RecordingService.EXTRA_DEFER_CLIPBOARD, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Optimistic; onStartListening corrects it from the real state next time.
        setTileState(true)
    }

    private fun prefs() =
        getSharedPreferences(RecordingService.PREFS_NAME, Context.MODE_PRIVATE)

    private fun isRecording(): Boolean =
        prefs().getBoolean(RecordingService.PREF_IS_RECORDING, false)

    private fun isReadyToRecord(): Boolean {
        if (prefs().getString("api_key", "").isNullOrBlank()) return false
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasMic && hasNotifications
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun setTileState(recording: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Stow"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (recording) "Recording" else "Tap to record"
        }
        tile.updateTile()
    }
}
