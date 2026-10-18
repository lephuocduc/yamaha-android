package vn.tftlink.data.model.location

import com.google.android.gms.maps.model.LatLng
import java.util.UUID

/**
 * Địa điểm đã lưu (Nhà, Cơ quan, Yêu thích, Trạm xăng) kết nối với Menu đồng hồ xe Yamaha
 */
data class SavedPlace(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var address: String = "",
    var latitude: Double,
    var longitude: Double
) {
    val coordinate: LatLng
        get() = LatLng(latitude, longitude)
}
