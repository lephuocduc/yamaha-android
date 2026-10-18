package vn.tftlink.data.model.location

import com.google.android.gms.maps.model.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Mô hình lưu trữ vị trí đỗ xe (Find My Bike / Vị trí xe lần cuối ngắt kết nối)
 */
data class ParkedBikeLocation(
    val id: String = UUID.randomUUID().toString(),
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val address: String = "",
    val bikeName: String = "Yamaha NMAX"
) {
    val coordinate: LatLng
        get() = LatLng(latitude, longitude)

    val formattedTimestamp: String
        get() {
            val sdf = SimpleDateFormat("HH:mm · dd/MM/yyyy", Locale("vi", "VN"))
            return sdf.format(Date(timestamp))
        }

    val relativeTimeString: String
        get() {
            val elapsedSeconds = (System.currentTimeMillis() - timestamp) / 1000
            return when {
                elapsedSeconds < 60 -> "Vừa mới đỗ xe"
                elapsedSeconds < 3600 -> "Đỗ cách đây ${elapsedSeconds / 60} phút"
                elapsedSeconds < 86400 -> {
                    val hours = elapsedSeconds / 3600
                    val minutes = (elapsedSeconds % 3600) / 60
                    if (minutes > 0) "Đỗ cách đây $hours giờ $minutes phút" else "Đỗ cách đây $hours giờ"
                }
                else -> {
                    val days = elapsedSeconds / 86400
                    "Đỗ cách đây $days ngày"
                }
            }
        }
}
