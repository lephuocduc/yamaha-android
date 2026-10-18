package vn.tftlink.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground Service quản lý BLE CCU & định vị GPS thời gian thực
 */
class BikeForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
