package vn.tftlink.data.model.location

import com.google.android.gms.maps.model.LatLng
import java.util.UUID

/**
 * Điểm đến nhanh phân loại theo loại hình
 */
data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var subtitle: String,
    var latitude: Double,
    var longitude: Double,
    var type: LocationType
) {
    enum class LocationType(val displayNameVi: String) {
        HOME("Nhà"),
        WORK("Cơ quan"),
        GAS("Trạm xăng"),
        FAVORITE("Yêu thích"),
        PARKING("Vị trí đỗ xe")
    }

    val coordinate: LatLng
        get() = LatLng(latitude, longitude)
}
