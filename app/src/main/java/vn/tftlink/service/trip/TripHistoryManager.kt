package vn.tftlink.service.trip

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.tftlink.data.local.AppDatabase
import vn.tftlink.data.model.trip.TripRecord
import vn.tftlink.service.nav.NavigationEngine
import vn.tftlink.service.nav.ParkingLocationManager
import vn.tftlink.util.GeoUtils
import java.util.Locale
import kotlin.math.roundToInt

import kotlinx.coroutines.SupervisorJob

class TripHistoryManager private constructor(
    private val context: Context
) {
    companion object {
        @Volatile
        private var instance: TripHistoryManager? = null

        fun getInstance(context: Context): TripHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: TripHistoryManager(context.applicationContext).also { instance = it }
            }
        }

        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context, scope: CoroutineScope): TripHistoryManager {
            return getInstance(context)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tripDao = AppDatabase.getInstance(context).tripDao()

    val allTrips: StateFlow<List<TripRecord>> = tripDao.getAllTrips()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live session state
    private val _isRecordingSession = MutableStateFlow(false)
    val isRecordingSession: StateFlow<Boolean> = _isRecordingSession.asStateFlow()

    private val _sessionDistanceMeters = MutableStateFlow(0.0)
    val sessionDistanceMeters: StateFlow<Double> = _sessionDistanceMeters.asStateFlow()

    private val _sessionDurationSeconds = MutableStateFlow(0L)
    val sessionDurationSeconds: StateFlow<Long> = _sessionDurationSeconds.asStateFlow()

    private val _sessionMaxSpeedKmh = MutableStateFlow(0.0)
    val sessionMaxSpeedKmh: StateFlow<Double> = _sessionMaxSpeedKmh.asStateFlow()

    private var sessionStartTimeMs: Long = 0
    private var sessionStartCoord: LatLng? = null
    private var sessionStartAddress: String = ""
    private var lastRecordedCoord: LatLng? = null
    private var sessionBikeName: String = "Yamaha NMAX"
    private val sessionPoints = mutableListOf<LatLng>()

    fun startTripSession(startCoord: LatLng, bikeName: String = "Yamaha NMAX") {
        if (_isRecordingSession.value) return
        if (startCoord.latitude == 0.0 && startCoord.longitude == 0.0) return

        sessionStartTimeMs = System.currentTimeMillis()
        sessionStartCoord = startCoord
        lastRecordedCoord = startCoord
        sessionBikeName = bikeName
        _sessionDistanceMeters.value = 0.0
        _sessionDurationSeconds.value = 0L
        _sessionMaxSpeedKmh.value = 0.0
        _isRecordingSession.value = true
        synchronized(sessionPoints) {
            sessionPoints.clear()
            sessionPoints.add(startCoord)
        }

        sessionStartAddress = String.format(Locale.US, "%.5f, %.5f", startCoord.latitude, startCoord.longitude)
        NavigationEngine.getInstance(context, scope).reverseGeocode(startCoord) { title, address ->
            sessionStartAddress = if (address.isNotBlank()) address else title
        }
    }

    fun updateLocation(loc: LatLng, speedKmh: Double = 0.0) {
        if (!_isRecordingSession.value) {
            // Tự động bắt đầu ghi lộ trình nếu xe chạy > 6 km/h
            if (speedKmh > 6.0) {
                startTripSession(loc, sessionBikeName)
            }
            return
        }

        val last = lastRecordedCoord
        if (last != null) {
            val delta = GeoUtils.computeDistanceMeters(last, loc)
            if (delta >= 2.0) { // Lọc nhiễu GPS đứng yên
                _sessionDistanceMeters.value += delta
                lastRecordedCoord = loc

                synchronized(sessionPoints) {
                    val lastPt = sessionPoints.lastOrNull()
                    if (lastPt == null || GeoUtils.computeDistanceMeters(lastPt, loc) >= 15.0) {
                        sessionPoints.add(loc)
                    }
                }
            }
        }

        if (speedKmh > _sessionMaxSpeedKmh.value) {
            _sessionMaxSpeedKmh.value = speedKmh
        }

        if (sessionStartTimeMs > 0) {
            _sessionDurationSeconds.value = (System.currentTimeMillis() - sessionStartTimeMs) / 1000
        }
    }

    fun endTripSession(endCoord: LatLng? = null, onSaved: ((TripRecord) -> Unit)? = null) {
        if (!_isRecordingSession.value) return
        _isRecordingSession.value = false

        val endTimeMs = System.currentTimeMillis()
        val durationSec = ((endTimeMs - sessionStartTimeMs) / 1000).coerceAtLeast(1)
        val distance = _sessionDistanceMeters.value
        val finalEndCoord = endCoord ?: lastRecordedCoord ?: sessionStartCoord ?: LatLng(0.0, 0.0)
        val maxSpeed = _sessionMaxSpeedKmh.value
        val avgSpeed = if (durationSec > 0) (distance / durationSec) * 3.6 else 0.0

        // Chỉ lưu chuyến đi nếu di chuyển > 30 mét hoặc > 20 giây
        if (distance < 30.0 && durationSec < 20) {
            synchronized(sessionPoints) { sessionPoints.clear() }
            return
        }

        val pointsCopy: List<LatLng>
        synchronized(sessionPoints) {
            if (finalEndCoord.latitude != 0.0 && finalEndCoord.longitude != 0.0) {
                val lastPt = sessionPoints.lastOrNull()
                if (lastPt == null || GeoUtils.computeDistanceMeters(lastPt, finalEndCoord) >= 5.0) {
                    sessionPoints.add(finalEndCoord)
                }
            }
            pointsCopy = sessionPoints.toList()
            sessionPoints.clear()
        }

        val polylineJson = try {
            com.google.gson.Gson().toJson(pointsCopy.map { listOf(it.latitude, it.longitude) })
        } catch (_: Exception) {
            "[]"
        }

        scope.launch(Dispatchers.IO) {
            val (title, address) = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                NavigationEngine.getInstance(context, scope).reverseGeocodeSuspend(finalEndCoord)
            } ?: Pair("Điểm đến", String.format(Locale.US, "%.5f, %.5f", finalEndCoord.latitude, finalEndCoord.longitude))
            val endAddress = if (address.isNotBlank()) address else title

            var finalStartAddress = sessionStartAddress
            if (finalStartAddress.matches(Regex("""^-?\d+\.\d+,\s*-?\d+\.\d+$""")) && sessionStartCoord != null) {
                val (sTitle, sAddr) = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                    NavigationEngine.getInstance(context, scope).reverseGeocodeSuspend(sessionStartCoord!!)
                } ?: Pair("", "")
                if (sAddr.isNotBlank()) finalStartAddress = sAddr
                else if (sTitle.isNotBlank()) finalStartAddress = sTitle
            }

            val record = TripRecord(
                startTime = sessionStartTimeMs,
                endTime = endTimeMs,
                startLatitude = sessionStartCoord?.latitude ?: 0.0,
                startLongitude = sessionStartCoord?.longitude ?: 0.0,
                startAddress = finalStartAddress,
                endLatitude = finalEndCoord.latitude,
                endLongitude = finalEndCoord.longitude,
                endAddress = endAddress,
                distanceMeters = distance,
                durationSeconds = durationSec,
                averageSpeedKmh = (avgSpeed * 10).roundToInt() / 10.0,
                maxSpeedKmh = (maxSpeed * 10).roundToInt() / 10.0,
                parkingTimestamp = endTimeMs,
                bikeName = sessionBikeName,
                polylineJson = polylineJson
            )

            tripDao.insertTrip(record)

            // Đồng thời ghi nhận vị trí đỗ xe MỚI với thời gian kết thúc lộ trình và địa chỉ đã tra cứu chi tiết
            ParkingLocationManager.getInstance(context, scope).recordParkingLocation(
                coordinate = finalEndCoord,
                bikeName = sessionBikeName,
                timestamp = endTimeMs,
                explicitAddress = endAddress,
                forceNewTimestamp = true
            )

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onSaved?.invoke(record)
            }
        }
    }

    suspend fun insertTrip(record: TripRecord) {
        tripDao.insertTrip(record)
    }

    fun deleteTrip(id: String) {
        scope.launch(Dispatchers.IO) {
            tripDao.deleteTrip(id)
        }
    }

    fun clearAllTrips() {
        scope.launch(Dispatchers.IO) {
            tripDao.clearAllTrips()
        }
    }
}
