package vn.tftlink.data.model.bike

import java.util.UUID

/**
 * Thông tin thiết bị Yamaha CCU / Màn hình Garmin StreetCross qua BLE
 */
data class BikeDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String, // Bluetooth MAC Address
    val rssi: Int = 0,
    val isPaired: Boolean = false
)
