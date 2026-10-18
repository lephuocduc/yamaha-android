package vn.tftlink.data.model.nav

import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tuyến đường lộ trình dẫn đường (Hỗ trợ đa tuyến / Tuyến thay thế phong cách Google Maps)
 */
data class OSMRoute(
    val id: String = UUID.randomUUID().toString(),
    val coordinates: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<NavigationStep> = emptyList(),
    var routeName: String = "",              // Ví dụ: "Qua Đ. Pasteur" hoặc "Tuyến chính"
    var isFastest: Boolean = true,           // Tuyến có thời gian nhanh nhất
    var timeDifferenceSeconds: Double = 0.0  // Chênh lệch thời gian so với tuyến nhanh nhất
) {
    val formattedDuration: String
        get() {
            val mins = max(1, (durationSeconds / 60.0).roundToInt())
            return if (mins < 60) {
                "$mins phút"
            } else {
                val h = mins / 60
                val m = mins % 60
                if (m > 0) "${h}h ${m}p" else "$h giờ"
            }
        }

    val formattedDistance: String
        get() = if (distanceMeters < 1000) {
            "${distanceMeters.toInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        }

    val timeDiffString: String
        get() {
            if (isFastest || timeDifferenceSeconds <= 30) {
                return "Nhanh nhất"
            }
            val mins = max(1, (timeDifferenceSeconds / 60.0).roundToInt())
            return "+$mins phút"
        }
}
