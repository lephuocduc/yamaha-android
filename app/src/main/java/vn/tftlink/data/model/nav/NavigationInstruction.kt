package vn.tftlink.data.model.nav

import java.util.Locale

/**
 * Chỉ dẫn điều hướng Turn-by-Turn hiển thị trên Android & Stream lên cụm đồng hồ xe
 */
data class NavigationInstruction(
    var direction: NavigationDirection = NavigationDirection.STRAIGHT,
    var distanceMeters: Int = 0,
    var currentRoadName: String = "",
    var nextRoadName: String = "",
    var totalDistanceRemainingMeters: Int = 0,
    var totalTimeRemainingSeconds: Int = 0,
    var isNavigating: Boolean = false,
    var etaString: String = "",               // Ví dụ: "12:45"
    var remainingTimeFormatted: String = "",  // Ví dụ: "18 phút" hoặc "1h 25p"
    var detailedInstruction: String = "",     // Ví dụ: "Rẽ phải vào đường Lê Lợi"
    var destinationName: String = "",
    var hasArrived: Boolean = false
) {
    val formattedDistance: String
        get() = if (distanceMeters < 1000) {
            "$distanceMeters m"
        } else {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        }

    val formattedDistanceCompact: String
        get() = if (distanceMeters < 1000) {
            "${distanceMeters}m"
        } else {
            String.format(Locale.US, "%.1fkm", distanceMeters / 1000.0)
        }

    val formattedRemainingDistance: String
        get() = if (totalDistanceRemainingMeters < 1000) {
            "$totalDistanceRemainingMeters m"
        } else {
            String.format(Locale.US, "%.1f km", totalDistanceRemainingMeters / 1000.0)
        }

    val headerTurnText: String
        get() {
            val dist = formattedDistanceCompact
            val action = direction.actionPhraseEn
            val road = nextRoadName.trim()
            if (direction == NavigationDirection.DESTINATION) {
                val dest = if (destinationName.isEmpty()) "destination" else destinationName
                return "$dist $action $dest"
            }
            if (road.isEmpty()) {
                val actionOnly = when (direction) {
                    NavigationDirection.STRAIGHT -> "go straight"
                    NavigationDirection.SLIGHT_RIGHT -> "slight right"
                    NavigationDirection.RIGHT -> "turn right"
                    NavigationDirection.SHARP_RIGHT -> "sharp right"
                    NavigationDirection.U_TURN -> "make U-turn"
                    NavigationDirection.SHARP_LEFT -> "sharp left"
                    NavigationDirection.LEFT -> "turn left"
                    NavigationDirection.SLIGHT_LEFT -> "slight left"
                    NavigationDirection.ROUNDABOUT -> "enter roundabout"
                    NavigationDirection.DESTINATION -> "arrive at destination"
                }
                return "$dist $actionOnly"
            }
            return "$dist $action $road"
        }
}
