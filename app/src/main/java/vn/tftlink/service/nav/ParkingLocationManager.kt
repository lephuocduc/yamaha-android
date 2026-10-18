package vn.tftlink.service.nav

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.tftlink.data.model.location.ParkedBikeLocation
import vn.tftlink.util.GeoUtils
import java.util.Locale

class ParkingLocationManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val PREFS_NAME = "nconnect_parking_prefs"
        private const val KEY_PARKED_LOCATION = "key_parked_bike_location"

        @Volatile
        private var instance: ParkingLocationManager? = null

        fun getInstance(context: Context, scope: CoroutineScope): ParkingLocationManager {
            return instance ?: synchronized(this) {
                instance ?: ParkingLocationManager(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _lastParkedLocation = MutableStateFlow<ParkedBikeLocation?>(null)
    val lastParkedLocation: StateFlow<ParkedBikeLocation?> = _lastParkedLocation.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val json = prefs.getString(KEY_PARKED_LOCATION, null)
        if (!json.isNullOrEmpty()) {
            try {
                val loc = gson.fromJson(json, ParkedBikeLocation::class.java)
                _lastParkedLocation.value = loc
            } catch (_: Exception) {}
        }
    }

    private fun saveToPrefs() {
        val loc = _lastParkedLocation.value
        if (loc != null) {
            val json = gson.toJson(loc)
            prefs.edit().putString(KEY_PARKED_LOCATION, json).apply()
        } else {
            prefs.edit().remove(KEY_PARKED_LOCATION).apply()
        }
    }

    /**
     * Ghi nhận vị trí đỗ xe khi xe ngắt kết nối hoặc tắt khóa điện
     */
    fun recordParkingLocation(
        coordinate: LatLng,
        bikeName: String = "Yamaha NMAX",
        timestamp: Long = System.currentTimeMillis(),
        explicitAddress: String? = null,
        forceNewTimestamp: Boolean = false
    ) {
        if (coordinate.latitude == 0.0 && coordinate.longitude == 0.0) return

        var effectiveTimestamp = timestamp
        val prev = _lastParkedLocation.value
        if (!forceNewTimestamp && prev != null) {
            val dist = GeoUtils.computeDistanceMeters(prev.coordinate, coordinate)
            // Nếu xe bật khóa rồi tắt nhưng không di chuyển (< 50m) -> giữ nguyên mốc thời gian đỗ trước đó
            if (dist < 50.0) {
                effectiveTimestamp = prev.timestamp
            }
        }

        val initialAddress = explicitAddress ?: String.format(Locale.US, "%.5f, %.5f", coordinate.latitude, coordinate.longitude)
        val parked = ParkedBikeLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            timestamp = effectiveTimestamp,
            address = initialAddress,
            bikeName = bikeName
        )

        _lastParkedLocation.value = parked
        saveToPrefs()

        // Nếu chưa có địa chỉ tường minh, tra cứu reverse geocode
        if (explicitAddress == null) {
            NavigationEngine.getInstance(context, scope).reverseGeocode(coordinate) { title, address ->
                val resolved = if (address.isNotBlank()) address else title
                val updated = _lastParkedLocation.value?.copy(address = resolved)
                if (updated != null) {
                    _lastParkedLocation.value = updated
                    saveToPrefs()
                }
            }
        }
    }

    fun clearParkingLocation() {
        _lastParkedLocation.value = null
        saveToPrefs()
    }

    /**
     * Tính khoảng cách từ người dùng tới vị trí xe
     */
    fun getDistanceMeters(userLoc: LatLng?): Double? {
        val bikeLoc = _lastParkedLocation.value?.coordinate ?: return null
        val user = userLoc ?: return null
        return GeoUtils.computeDistanceMeters(user, bikeLoc)
    }

    fun getFormattedDistance(userLoc: LatLng?): String {
        val dist = getDistanceMeters(userLoc) ?: return "--"
        return if (dist < 1000) {
            "${dist.toInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", dist / 1000.0)
        }
    }

    /**
     * Mở Google Maps chỉ đường đi bộ tới xe
     */
    fun openGoogleMapsWalking(context: Context) {
        val parked = _lastParkedLocation.value ?: return
        val gmapsUri = Uri.parse("google.navigation:q=${parked.latitude},${parked.longitude}&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmapsUri).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(mapIntent)
        } catch (_: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${parked.latitude},${parked.longitude}&travelmode=walking")
            val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }
}
