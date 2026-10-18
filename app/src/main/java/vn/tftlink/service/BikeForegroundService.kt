package vn.tftlink.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vn.tftlink.data.model.bike.BikeConnectionState
import vn.tftlink.service.ble.YamahaCCUManager
import vn.tftlink.service.nav.NavigationEngine
import vn.tftlink.service.nav.ParkingLocationManager
import vn.tftlink.service.trip.TripHistoryManager
import vn.tftlink.ui.MainActivity

class BikeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nconnect_bike_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "vn.tftlink.action.START_SERVICE"
        const val ACTION_STOP = "vn.tftlink.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, BikeForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BikeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var ccuManager: YamahaCCUManager
    private lateinit var navEngine: NavigationEngine
    private lateinit var tripManager: TripHistoryManager
    private lateinit var parkingManager: ParkingLocationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        ccuManager = YamahaCCUManager.getInstance(applicationContext, serviceScope)
        navEngine = NavigationEngine.getInstance(applicationContext)
        tripManager = TripHistoryManager.getInstance(applicationContext)
        parkingManager = ParkingLocationManager.getInstance(applicationContext, serviceScope)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startForegroundWithNotification("Đang khởi động dịch vụ kết nối Yamaha CCU...")
        startLocationUpdates()
        observeSystemStates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dịch vụ nền kết nối xe Yamaha CCU & Dẫn đường",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì kết nối Bluetooth xe máy và định vị dẫn đường khi tắt màn hình"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyNMAX • Yamaha CCU Companion")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val latLng = LatLng(loc.latitude, loc.longitude)
                val speedKmh = loc.speed * 3.6

                navEngine.updateLocation(latLng, speedKmh, loc.bearing)
                tripManager.updateLocation(latLng, speedKmh)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    private fun observeSystemStates() {
        // Cập nhật thông báo theo trạng thái xe
        serviceScope.launch {
            ccuManager.state.collectLatest { state ->
                val bikeName = ccuManager.connectedBike.value?.name ?: "Yamaha NMAX"
                when (state) {
                    BikeConnectionState.CONNECTED, BikeConnectionState.STREAMING -> {
                        updateNotification("Đã kết nối $bikeName • Đang hoạt động")
                    }
                    BikeConnectionState.CONNECTING, BikeConnectionState.AUTHENTICATING -> {
                        updateNotification("Đang kết nối tới $bikeName...")
                    }
                    BikeConnectionState.DISCONNECTED -> {
                        updateNotification("Chưa kết nối xe • Đang chờ bật khóa điện")
                        // Tự động ghi lại vị trí đỗ xe khi ngắt kết nối
                        navEngine.currentLocation.value?.let { loc ->
                            parkingManager.recordParkingLocation(loc, bikeName)
                            tripManager.endTripSession(loc)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Cập nhật thông báo khi đang dẫn đường Turn-by-Turn
        serviceScope.launch {
            navEngine.currentInstruction.collectLatest { inst ->
                if (navEngine.isNavigating.value) {
                    val road = if (inst.nextRoadName.isNotBlank()) inst.nextRoadName else inst.detailedInstruction
                    val dist = inst.formattedDistance
                    updateNotification("Dẫn đường: ${inst.direction.actionPhraseVi} vào $road ($dist còn lại)")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        serviceScope.cancel()
    }
}
