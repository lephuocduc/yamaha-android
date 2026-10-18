package vn.tftlink.data.model.bike

/**
 * Phím bấm và sự kiện điều khiển từ cùm tay lái xe máy Yamaha
 */
sealed interface HandlebarButton {
    data object ZoomIn : HandlebarButton               // Mã 51, 57 (+)
    data object ZoomOut : HandlebarButton              // Mã 52, 58 (-)
    data object StartStreamOrOpenMenu : HandlebarButton // Mã 55 (sub 1)
    data object PauseStreamOrCloseMenu : HandlebarButton// Mã 56 (0x38)
    data object StopRoute : HandlebarButton             // Mã 49 (0x31)
    data object GoHome : HandlebarButton                // Mã 53 (0x35)
    data object GoToWork : HandlebarButton              // Mã 54 (0x36)
    data object OpenFavorites : HandlebarButton         // Mã 55 (sub 3)
    data object OpenGasStations : HandlebarButton       // Mã 55 (sub 4)
    data class SelectPoiItem(val index: Int, val listId: Int) : HandlebarButton // Mã 48 (0x30)
    data class Unknown(val code: Int) : HandlebarButton
}
