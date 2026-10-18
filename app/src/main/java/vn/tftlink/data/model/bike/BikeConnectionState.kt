package vn.tftlink.data.model.bike

/**
 * Trạng thái kết nối xe máy Yamaha CCU / Màn hình TFT
 */
enum class BikeConnectionState(val displayNameVi: String) {
    DISCONNECTED("Chưa kết nối"),
    SCANNING("Đang tìm xe…"),
    CONNECTING("Đang kết nối…"),
    AUTHENTICATING("Đang xác thực (Handshake)…"),
    CONNECTED("Đã kết nối với xe"),
    STREAMING("Đang truyền bản đồ lên màn hình TFT"),
    ERROR("Lỗi kết nối");

    val rawValue: String
        get() = displayNameVi

    val isConnectedOrStreaming: Boolean
        get() = this == CONNECTED || this == STREAMING
}
