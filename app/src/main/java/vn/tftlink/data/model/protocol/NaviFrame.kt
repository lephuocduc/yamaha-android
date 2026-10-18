package vn.tftlink.data.model.protocol

/**
 * Khung tin dữ liệu NaviLite Wire Frame (Garmin StreetCross wire format on Yamaha TFT)
 */
data class NaviFrame(
    val svc: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NaviFrame
        if (svc != other.svc) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = svc
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
