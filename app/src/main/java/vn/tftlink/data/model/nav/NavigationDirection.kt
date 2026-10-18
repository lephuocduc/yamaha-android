package vn.tftlink.data.model.nav

/**
 * Hướng rẽ dẫn đường (Maneuver) đồng bộ giữa điện thoại và màn hình xe Yamaha
 */
enum class NavigationDirection(val code: Int) {
    STRAIGHT(1),
    SLIGHT_RIGHT(2),
    RIGHT(3),
    SHARP_RIGHT(4),
    U_TURN(5),
    SHARP_LEFT(6),
    LEFT(7),
    SLIGHT_LEFT(8),
    ROUNDABOUT(9),
    DESTINATION(10);

    val descriptionVi: String
        get() = when (this) {
            STRAIGHT -> "Đi thẳng"
            SLIGHT_RIGHT -> "Chếch sang phải"
            RIGHT -> "Rẽ phải"
            SHARP_RIGHT -> "Rẽ gắt sang phải"
            U_TURN -> "Quay đầu xe"
            SHARP_LEFT -> "Rẽ gắt sang trái"
            LEFT -> "Rẽ trái"
            SLIGHT_LEFT -> "Chếch sang trái"
            ROUNDABOUT -> "Vào vòng xuyến"
            DESTINATION -> "Đến điểm đích"
        }

    val actionPhraseEn: String
        get() = when (this) {
            STRAIGHT -> "straight onto"
            SLIGHT_RIGHT -> "slight right onto"
            RIGHT -> "turn right onto"
            SHARP_RIGHT -> "sharp right onto"
            U_TURN -> "make U-turn onto"
            SHARP_LEFT -> "sharp left onto"
            LEFT -> "turn left onto"
            SLIGHT_LEFT -> "slight left onto"
            ROUNDABOUT -> "enter roundabout onto"
            DESTINATION -> "arrive at"
        }

    companion object {
        fun fromCode(code: Int): NavigationDirection {
            return entries.firstOrNull { it.code == code } ?: STRAIGHT
        }
    }
}
