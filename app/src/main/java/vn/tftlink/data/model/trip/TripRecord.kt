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
}
