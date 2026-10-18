package vn.tftlink.data.model.nav

import com.google.android.gms.maps.model.LatLng
import java.util.UUID

/**
 * Từng bước rẽ trong lộ trình (Valhalla / OSRM / Google Directions step)
 */
data class NavigationStep(
    val id: String = UUID.randomUUID().toString(),
    val instruction: String,
    val distanceMeters: Int,
    val direction: NavigationDirection,
    val coordinate: LatLng,
    val roadName: String
)
