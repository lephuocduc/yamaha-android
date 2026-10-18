package vn.tftlink.data.model.bike

/**
 * Thông số tức thời từ xe máy Yamaha qua BLE
 */
data class BikeTelemetry(
    var speedKmh: Double = 0.0,
    var rpm: Int = 0,
    var fuelPercent: Int = 0,
    var engineTemperature: Double = 0.0,
    var batteryVoltage: Double = 0.0,
    var hasError: Boolean = false,
    var errorCode: String = ""
)
