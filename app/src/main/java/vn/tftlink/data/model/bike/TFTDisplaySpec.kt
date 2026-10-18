package vn.tftlink.data.model.bike

/**
 * Thông số màn hình TFT kỹ thuật số nhận diện từ xe máy Yamaha
 */
data class TFTDisplaySpec(
    var width: Int = 480,
    var height: Int = 234,
    var softwarePart: String = "006-B4160",
    var isReadyForStreaming: Boolean = false
)
