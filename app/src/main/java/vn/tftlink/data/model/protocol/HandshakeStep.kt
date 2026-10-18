package vn.tftlink.data.model.protocol

/**
 * Các bước bắt tay (Handshake) với cụm đồng hồ xe Yamaha / Garmin StreetCross
 */
enum class HandshakeStep(val stepNumber: Int) {
    NOT_STARTED(0),
    STEP1_SERIAL_RECEIVED(1),
    STEP2_DIMENSIONS_NEGOTIATED(2),
    STEP3_FULLY_AUTHENTICATED(3)
}
