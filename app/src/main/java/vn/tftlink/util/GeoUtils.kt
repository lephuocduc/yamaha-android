package vn.tftlink.util

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Tính khoảng cách giữa 2 toạ độ LatLng theo công thức Haversine (đơn vị: mét)
     */
    fun computeDistanceMeters(start: LatLng, end: LatLng): Double {
        val latDistance = Math.toRadians(end.latitude - start.latitude)
        val lonDistance = Math.toRadians(end.longitude - start.longitude)
        val a = sin(latDistance / 2) * sin(latDistance / 2) +
                cos(Math.toRadians(start.latitude)) * cos(Math.toRadians(end.latitude)) *
                sin(lonDistance / 2) * sin(lonDistance / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Tính tổng chiều dài lộ trình từ danh sách các điểm toạ độ
     */
    fun computeTotalDistance(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += computeDistanceMeters(points[i], points[i + 1])
        }
        return total
    }
}
