package vn.tftlink.service.protocol

import vn.tftlink.data.model.bike.HandlebarButton
import vn.tftlink.data.model.bike.TFTDisplaySpec
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.protocol.HandshakeStep
import vn.tftlink.data.model.protocol.NaviFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Giao thức Garmin StreetCross dành cho màn hình TFT xe máy Yamaha
 */
class StreetCrossProtocol {

    companion object {
        const val STREET_CROSS_UUID = "00007220-0000-1000-8000-00805F9B34FB"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        const val SOFTWARE_PART_NUMBER = "006-B4160" // Mã phần mềm chuẩn cho XMAX / NMAX
        const val FALLBACK_PART_NUMBER = "006-B3952-10"
    }

    var currentStep: HandshakeStep = HandshakeStep.NOT_STARTED
        private set

    var displaySpec: TFTDisplaySpec = TFTDisplaySpec()
    var frameIndex: Int = 0
        private set

    // Callbacks xử lý sự kiện từ xe
    var onHandshakeCompleted: ((TFTDisplaySpec) -> Unit)? = null
    var onHandlebarButtonReceived: ((HandlebarButton) -> Unit)? = null
    var onFrameAckReceived: ((Long) -> Unit)? = null

    fun reset() {
        currentStep = HandshakeStep.NOT_STARTED
        frameIndex = 0
        displaySpec = TFTDisplaySpec()
    }

    /**
     * Xử lý gói tin nhận từ xe máy qua Bluetooth
     */
    fun processIncomingData(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val command = data[0].toInt() and 0xFF

        when (currentStep) {
            HandshakeStep.NOT_STARTED, HandshakeStep.STEP1_SERIAL_RECEIVED -> {
                // Bắt tay bước 1: Xe gửi Serial máy
                // Phản hồi: Lệnh 81 (0x51) + 33 (0x21)
                if (command == 0x01 || data.size >= 8) {
                    currentStep = HandshakeStep.STEP1_SERIAL_RECEIVED
                    return byteArrayOf(0x51.toByte(), 0x21.toByte(), 0x00, 0x00)
                }
            }

            HandshakeStep.STEP2_DIMENSIONS_NEGOTIATED -> {
                // Bắt tay bước 2 & 3: Xe xác thực và gửi kích thước màn hình
                if (command == 0x53 || command == 0x83) {
                    var width = 480
                    var height = 234
                    if (data.size >= 6) {
                        width = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                        height = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
                        if (width <= 0 || width > 1920) width = 480
                        if (height <= 0 || height > 1080) height = 234
                    }

                    displaySpec = TFTDisplaySpec(width, height, SOFTWARE_PART_NUMBER, true)

                    // Trả về gói 84 (0x54) xác nhận kích thước + part number
                    val partBytes = SOFTWARE_PART_NUMBER.toByteArray(Charsets.US_ASCII)
                    val resp = ByteArray(2 + partBytes.size)
                    resp[0] = 0x54.toByte()
                    resp[1] = 0x01.toByte()
                    System.arraycopy(partBytes, 0, resp, 2, partBytes.size)
                    return resp
                } else if (command == 0x82) {
                    // Bắt tay 3/3: Xe xác nhận (82 = 1)
                    val status = if (data.size > 1) data[1].toInt() and 0xFF else 0
                    if (status == 1) {
                        currentStep = HandshakeStep.STEP3_FULLY_AUTHENTICATED
                        displaySpec.isReadyForStreaming = true
                        onHandshakeCompleted?.invoke(displaySpec)
                        return buildInitialConfigPacket()
                    }
                }
            }

            HandshakeStep.STEP3_FULLY_AUTHENTICATED -> {
                // Xử lý các lệnh điều khiển từ phím bấm tay lái khi đang chạy
                return handleOperationalCommands(command, data)
            }
        }
        return null
    }

    /**
     * Xử lý lệnh vận hành & phím bấm tay lái
     */
    private fun handleOperationalCommands(command: Int, bytes: ByteArray): ByteArray? {
        when (command) {
            51, 57 -> { // Phím (+) / Zoom In
                onHandlebarButtonReceived?.invoke(HandlebarButton.ZoomIn)
                return null
            }
            52, 58 -> { // Phím (-) / Zoom Out
                onHandlebarButtonReceived?.invoke(HandlebarButton.ZoomOut)
                return null
            }
            53 -> { // 0x35: Về nhà
                onHandlebarButtonReceived?.invoke(HandlebarButton.GoHome)
                return null
            }
            54 -> { // 0x36: Đi làm
                onHandlebarButtonReceived?.invoke(HandlebarButton.GoToWork)
                return null
            }
            49 -> { // 0x31: Dừng lộ trình
                onHandlebarButtonReceived?.invoke(HandlebarButton.StopRoute)
                return null
            }
            55 -> { // 0x37: Mở danh sách hoặc bắt đầu nhận hình
                val sub = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 1
                when (sub) {
                    3 -> onHandlebarButtonReceived?.invoke(HandlebarButton.OpenFavorites)
                    4 -> onHandlebarButtonReceived?.invoke(HandlebarButton.OpenGasStations)
                    else -> onHandlebarButtonReceived?.invoke(HandlebarButton.StartStreamOrOpenMenu)
                }
                return null
            }
            56 -> { // 0x38: Xe tạm dừng nhận hình -> Phản hồi 20 (0x14)
                onHandlebarButtonReceived?.invoke(HandlebarButton.PauseStreamOrCloseMenu)
                return byteArrayOf(0x14.toByte(), 0x00)
            }
            48 -> { // 0x30: Chọn mục POI từ danh sách
                val itemIdx = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
                val listId = if (bytes.size > 2) bytes[2].toInt() and 0xFF else 0
                onHandlebarButtonReceived?.invoke(HandlebarButton.SelectPoiItem(itemIdx, listId))
                return null
            }
            0x06 -> { // ACK khung hình từ màn hình TFT
                if (bytes.size >= 5) {
                    val ackIndex = (bytes[1].toLong() and 0xFF) or
                            ((bytes[2].toLong() and 0xFF) shl 8) or
                            ((bytes[3].toLong() and 0xFF) shl 16) or
                            ((bytes[4].toLong() and 0xFF) shl 24)
                    onFrameAckReceived?.invoke(ackIndex)
                }
                return null
            }
            else -> {
                onHandlebarButtonReceived?.invoke(HandlebarButton.Unknown(command))
                return null
            }
        }
    }

    private fun buildInitialConfigPacket(): ByteArray {
        return byteArrayOf(
            0x20.toByte(), 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x00
        )
    }

    /**
     * Đóng gói Frame ảnh JPEG nén để stream lên màn hình TFT qua BLE
     * Header: [0x05, FrameIdx (4B LE), TotalSize (4B LE)] + chunks <= 500B
     */
    fun encodeJPEGFrameForTFT(jpegData: ByteArray): List<ByteArray> {
        if (jpegData.size < 2048) return emptyList()

        frameIndex++
        val currentIdx = frameIndex

        val frameHeader = ByteArray(9)
        frameHeader[0] = 0x05
        val buf = ByteBuffer.wrap(frameHeader, 1, 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(currentIdx)
        buf.putInt(jpegData.size)

        val fullPayload = ByteArray(frameHeader.size + jpegData.size)
        System.arraycopy(frameHeader, 0, fullPayload, 0, frameHeader.size)
        System.arraycopy(jpegData, 0, fullPayload, frameHeader.size, jpegData.size)

        val chunkSize = 500
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < fullPayload.size) {
            val chunkLen = minOf(chunkSize, fullPayload.size - offset)
            val chunk = ByteArray(chunkLen)
            System.arraycopy(fullPayload, offset, chunk, 0, chunkLen)
            packets.add(chunk)
            offset += chunkLen
        }
        return packets
    }

    /**
     * Gói tin Turn-by-Turn (Mũi tên chỉ đường + Khoảng cách + Tên đường)
     */
    fun buildTurnByTurnPacket(instruction: NavigationInstruction): ByteArray {
        val roadBytes = instruction.nextRoadName.toByteArray(Charsets.UTF_8)
        val packet = ByteBuffer.allocate(8 + roadBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        packet.put(0x30.toByte()) // Lệnh hiển thị dẫn đường
        packet.put(instruction.direction.code.toByte()) // Hướng rẽ
        packet.putInt(instruction.distanceMeters) // Cự ly LE
        packet.putShort(roadBytes.size.toShort()) // Độ dài tên đường LE
        packet.put(roadBytes)
        return packet.array()
    }
}

/**
 * Bộ mã hóa/giải mã khung tin NaviLite wire format (Garmin StreetCross wire format on Yamaha TFT)
 */
object NaviLite {
    const val FT_PHONE: Byte = 6

    private val CRC_TABLE: IntArray = IntArray(256) { i ->
        var c = i shl 24
        for (j in 0 until 8) {
            c = if ((c and -0x80000000) != 0) (c shl 1) xor 0x04C11DB7 else c shl 1
        }
        c
    }

    /**
     * Thuật toán tính CRC32-MPEG2 chuẩn Garmin / Yamaha CCU
     */
    fun crc32Mpeg2(data: ByteArray): Int {
        var c = -1
        for (b in data) {
            c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return c
    }

    /**
     * Đóng gói khung tin NaviLite:
     * "nAl@" | version 1 | frameType | serviceType | size(4 LE) | pdt | crc(4 LE) | payload
     */
    fun frame(ft: Byte, svc: Byte, pdt: Byte, payload: ByteArray): ByteArray {
        val size = payload.size
        val h = ByteArray(9)
        h[0] = 0x6e // 'n'
        h[1] = 0x41 // 'A'
        h[2] = 0x6c // 'l'
        h[3] = 0x40 // '@'
        h[4] = 1    // version 1
        h[5] = ft   // frameType (e.g. 6 = PHONE)
        h[6] = svc  // serviceType
        h[7] = (size and 0xFF).toByte()
        h[8] = ((size shr 8) and 0xFF).toByte()

        // Full header 13 bytes for CRC calculation: magic(4) + ver(1) + ft(1) + svc(1) + size(4) + pdt(1)
        val header = ByteArray(13)
        System.arraycopy(h, 0, header, 0, 7)
        val sizeBuf = ByteBuffer.wrap(header, 7, 4).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuf.putInt(size)
        header[11] = ((size shr 24) and 0xFF).toByte() // just in case
        header[12] = pdt // payloadDataType (0 = VALUE, 1 = POINTER)

        val fullForCrc = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, fullForCrc, 0, header.size)
        System.arraycopy(payload, 0, fullForCrc, header.size, payload.size)

        val crc = crc32Mpeg2(fullForCrc)

        val out = ByteBuffer.allocate(header.size + 4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(header)
        out.putInt(crc)
        out.put(payload)
        return out.array()
    }

    fun frame(ft: Int, svc: Int, pdt: Int, payload: ByteArray): ByteArray {
        return frame(ft.toByte(), svc.toByte(), pdt.toByte(), payload)
    }

    /**
     * Echo-auth: xe gửi hạt giống bảo mật (SEC_DATA); điện thoại phản hồi 4 byte cuối XOR 0x0A
     */
    fun secDataAckPayload(seed: ByteArray): ByteArray {
        val n = 4
        if (seed.size < n) return byteArrayOf(0, 0, 0, 0)
        val result = ByteArray(n)
        for (i in 0 until n) {
            result[i] = (seed[seed.size - n + i].toInt() xor 0x0A).toByte()
        }
        return result
    }

    fun partNumber(seed: ByteArray): String {
        if (seed.size <= 4) return "?"
        val raw = ByteArray(seed.size - 4)
        for (i in 0 until (seed.size - 4)) {
            raw[i] = (seed[i].toInt() xor 0x0A).toByte()
        }
        return String(raw, Charsets.US_ASCII)
    }

    fun hexB(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
