package vn.tftlink.data.model.trip

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Mô hình dữ liệu lưu trữ 1 chuyến đi (Lộ trình đã chạy)
 */
@Entity(tableName = "trip_records")
data class TripRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long,
    val endTime: Long,
    val startLatitude: Double,
    val startLongitude: Double,
    val startAddress: String,
    val endLatitude: Double,
    val endLongitude: Double,
    val endAddress: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val parkingTimestamp: Long,
    val bikeName: String = "Yamaha NMAX",
    val polylineJson: String = "[]" // JSON representation of List<LatLng>
) {
    @Ignore
    var polylinePoints: List<LatLng> = emptyList()

    val formattedDuration: String
        get() {
            val minutes = durationSeconds / 60
            val hours = minutes / 60
            val remainingMins = minutes % 60
            return if (hours > 0) {
                "${hours}h ${remainingMins}p"
            } else {
                "$minutes phút"
            }
        }

    val formattedDistance: String
        get() = if (distanceMeters < 1000) {
            "${distanceMeters.toInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN"))
            return sdf.format(Date(startTime))
        }

    val formattedTimeRange: String
        get() {
            val sdf = SimpleDateFormat("HH:mm", Locale("vi", "VN"))
            return "${sdf.format(Date(startTime))} - ${sdf.format(Date(endTime))}"
        }

    fun getParsedPolylinePoints(): List<LatLng> {
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<List<Double>>>() {}.type
            val raw: List<List<Double>> = com.google.gson.Gson().fromJson(polylineJson, listType) ?: emptyList()
            raw.mapNotNull {
                if (it.size >= 2) LatLng(it[0], it[1]) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val relativeParkingDuration: String
        get() {
            val elapsed = (System.currentTimeMillis() - parkingTimestamp) / 1000
            return when {
                elapsed < 60 -> "Vừa đỗ xe"
                elapsed < 3600 -> "Đã đỗ ${elapsed / 60} phút"
                elapsed < 86400 -> {
                    val h = elapsed / 3600
                    val m = (elapsed % 3600) / 60
                    if (m > 0) "Đã đỗ ${h}h ${m}p" else "Đã đỗ $h giờ"
                }
                else -> "Đã đỗ ${elapsed / 86400} ngày"
            }
        }
}
