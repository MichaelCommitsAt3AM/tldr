package tech.currenta.tldr

import android.app.Activity
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class OverlayTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "TLDR"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (OverlayService.isRunning) "Floating shortcut on" else "Floating shortcut off"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val toggle = Runnable {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
            } else {
                // A short-lived activity makes the user-initiated foreground-service start
                // compatible with Android's background launch restrictions.
                val intent = Intent(this, TileLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    startActivityAndCollapse(pending)
                } else {
                    collapseBeforeAndroid14(intent)
                }
            }
        }
        if (isLocked) unlockAndRun(toggle) else toggle.run()
    }

    // The PendingIntent overload was added in API 34. The lint detector also flags
    // this required legacy call even though the caller and this guard restrict it.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun collapseBeforeAndroid14(intent: Intent) {
        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        startActivityAndCollapse(intent)
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, OverlayTileService::class.java))
        }
    }
}

class TileLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            try {
                startForegroundService(Intent(this, OverlayService::class.java))
            } catch (_: IllegalStateException) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        finish()
    }
}
