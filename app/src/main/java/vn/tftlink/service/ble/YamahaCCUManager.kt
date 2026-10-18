package vn.tftlink.service.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.tftlink.data.model.bike.BikeConnectionState
import vn.tftlink.data.model.bike.BikeDevice
import vn.tftlink.data.model.bike.BikeTelemetry
import vn.tftlink.data.model.bike.HandlebarButton
import vn.tftlink.data.model.bike.TFTDisplaySpec
import vn.tftlink.data.model.protocol.NaviFrame
import vn.tftlink.service.protocol.NaviLite
import vn.tftlink.service.protocol.StreetCrossProtocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Quản lý kết nối Yamaha CCU (Hỗ trợ cả BLE Telemetry & Bluetooth SPP/GATT cho màn hình TFT)
 */
@SuppressLint("MissingPermission")
class YamahaCCUManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        val CLUSTER_LINK_SERVICE_UUID: UUID = UUID.fromString("AFA2CDF4-ECCF-46A7-A5EA-9DA428C0157A")
        val DDT_WRITE_CHAR_UUID: UUID = UUID.fromString("B606C7F9-E5A1-4E75-B313-2A920054A8EB")
        val DDT_NOTIFY_CHAR_UUID: UUID = UUID.fromString("75AB2788-87F6-4F61-BA9C-6F85A12600D1")
        val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("9C810D26-B605-4306-8C1C-755A1BA3066C")
        val STREET_CROSS_SERVICE_UUID: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        @Volatile
        private var instance: YamahaCCUManager? = null

        fun getInstance(context: Context, scope: CoroutineScope): YamahaCCUManager {
            return instance ?: synchronized(this) {
                instance ?: YamahaCCUManager(context.applicationContext, scope).also { instance = it }
            }
        }

        /**
         * Lọc thiết bị chuẩn Yamaha CCU, loại bỏ Smartwatch, Loa, Tai nghe, TV
         */
        fun isYamahaBikeCCU(name: String, serviceUuids: List<UUID> = emptyList()): Boolean {
            if (serviceUuids.contains(CLUSTER_LINK_SERVICE_UUID) || serviceUuids.contains(STREET_CROSS_SERVICE_UUID)) {
                return true
            }

            val trimmed = name.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase(Locale.ROOT)

            // Danh sách đen
            val blacklist = listOf(
                "watch", "forerunner", "fenix", "epix", "venu", "vivo", "instinct",
                "descent", "approach", "marq", "tactix", "enduro", "smartband", "band",
                "fitbit", "galaxy watch", "apple watch", "wearable",
                "soundbar", "speaker", "loa", "earbud", "earphone", "headphone", "headset",
                "airpod", "tai nghe", "receiver", "ampli", "amp", "musiccast",
                "audio", "subwoofer", "sound", "piano", "keyboard", "guitar", "clavinova",
                "disklavier", "organ", "tv", "tivi", "television", "bravia"
            )
            for (bad in blacklist) {
                if (lower.contains(bad)) return false
            }

            // Tiền tố định danh chuẩn CCU
            if (lower.startsWith("ccu-") || lower.startsWith("ccu_") || lower.startsWith("ccu") ||
                lower.startsWith("yccu_") || lower.startsWith("yccu-") || lower.startsWith("yccu") ||
                lower.startsWith("ys_") || lower.startsWith("ys-") || lower.startsWith("mynmax")
            ) {
                return true
            }

            // Tên tương thích Y-Connect
            if (lower.contains("y-connect") || lower.contains("yconnect") ||
                lower.contains("yamaha ccu") || lower.contains("yamaha-ccu") || lower.contains("yamaha_ccu")
            ) {
                return true
            }

            // Dòng xe máy Yamaha
            if (lower.contains("yamaha")) {
                val bikeModels = listOf(
                    "nmax", "nvx", "aerox", "xmax", "tmax", "tracer", "mt-", "mt0",
                    "r15", "r3", "r7", "r1", "fazzio", "filano", "pg-1", "lexi",
                    "ccu", "cluster", "meter", "dash", "moto", "bike", "tft"
                )
                return bikeModels.any { lower.contains(it) }
            }

            // Màn hình Garmin StreetCross
            if (lower.contains("garmin")) {
                val clusterKw = listOf("streetcross", "navilite", "cluster", "meter", "dash", "tft", "yamaha")
                return clusterKw.any { lower.contains(it) }
            }

            return false
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    // StateFlows
    private val _state = MutableStateFlow(BikeConnectionState.DISCONNECTED)
    val state: StateFlow<BikeConnectionState> = _state.asStateFlow()

    private val _discoveredBikes = MutableStateFlow<List<BikeDevice>>(emptyList())
    val discoveredBikes: StateFlow<List<BikeDevice>> = _discoveredBikes.asStateFlow()

    private val _connectedBike = MutableStateFlow<BikeDevice?>(null)
    val connectedBike: StateFlow<BikeDevice?> = _connectedBike.asStateFlow()

    private val _telemetry = MutableStateFlow(BikeTelemetry())
    val telemetry: StateFlow<BikeTelemetry> = _telemetry.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    private val _currentRoadNameOnTFT = MutableStateFlow("MyNMAX")
    val currentRoadNameOnTFT: StateFlow<String> = _currentRoadNameOnTFT.asStateFlow()

    private val _handlebarEvent = MutableSharedFlow<HandlebarButton>()
    val handlebarEvent: SharedFlow<HandlebarButton> = _handlebarEvent.asSharedFlow()

    // Callbacks xử lý nút bấm cùm tay lái
    var onZoomIn: (() -> Unit)? = null
    var onZoomOut: (() -> Unit)? = null
    var onStopRoute: (() -> Unit)? = null
    var onGoHome: (() -> Unit)? = null
    var onGoToWork: (() -> Unit)? = null
    var onFindGasStation: (() -> Unit)? = null
    var onOpenFavorites: (() -> Unit)? = null
    var onSelectPoi: ((Int, Int) -> Unit)? = null
    var onBikeConnected: (() -> Unit)? = null
    var onBikeDisconnected: (() -> Unit)? = null

    var lastSentRoadName: String = ""
    var lastSentFavorites: List<Pair<String, LatLng>> = emptyList()
    var lastSentGasStations: List<Pair<String, LatLng>> = emptyList()

    val streetCross = StreetCrossProtocol()

    // Biến BLE GATT
    private var bluetoothGatt: BluetoothGatt? = null
    private var ddtWriteChar: BluetoothGattCharacteristic? = null
    private var ddtNotifyChar: BluetoothGattCharacteristic? = null
    private var telemetryChar: BluetoothGattCharacteristic? = null

    // Kênh SPP / RFCOMM
    private var sppSocket: BluetoothSocket? = null
    private var sppInputStream: InputStream? = null
    private var sppOutputStream: OutputStream? = null
    private var sppJob: Job? = null
    private val sppOutQueue = ConcurrentLinkedQueue<ByteArray>()
    private var naviSeq: Int = 1
    private var wasConnectedToBike: Boolean = false

    init {
        streetCross.onHandlebarButtonReceived = { btn ->
            scope.launch(Dispatchers.Main) {
                _handlebarEvent.emit(btn)
                when (btn) {
                    is HandlebarButton.ZoomIn -> onZoomIn?.invoke()
                    is HandlebarButton.ZoomOut -> onZoomOut?.invoke()
                    is HandlebarButton.StopRoute -> onStopRoute?.invoke()
                    is HandlebarButton.GoHome -> onGoHome?.invoke()
                    is HandlebarButton.GoToWork -> onGoToWork?.invoke()
                    is HandlebarButton.OpenFavorites -> onOpenFavorites?.invoke()
                    is HandlebarButton.OpenGasStations -> onFindGasStation?.invoke()
                    is HandlebarButton.SelectPoiItem -> onSelectPoi?.invoke(btn.index, btn.listId)
                    else -> Unit
                }
            }
        }
    }

    fun log(msg: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val line = "[${sdf.format(Date())}] $msg"
        val current = _diagnosticLogs.value.toMutableList()
        current.add(line)
        if (current.size > 500) {
            current.removeAt(0)
        }
        _diagnosticLogs.value = current
    }

    // =========================================================================
    // QUÉT TÌM XE (BLE SCAN)
    // =========================================================================

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("✗ Quét BLE thất bại (Mã lỗi: $errorCode)")
            _state.value = BikeConnectionState.ERROR
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val name = device.name ?: result.scanRecord?.deviceName ?: ""
        if (name.isEmpty()) return

        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

        if (isYamahaBikeCCU(name, serviceUuids)) {
            val bike = BikeDevice(
                id = device.address,
                name = name,
                address = device.address,
                rssi = result.rssi,
                isPaired = device.bondState == BluetoothDevice.BOND_BONDED
            )

            val current = _discoveredBikes.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.address == bike.address }
            if (existingIdx == -1) {
                log("Thấy xe Yamaha hợp lệ: $name (${device.address}), RSSI: ${result.rssi}")
                current.add(bike)
                _discoveredBikes.value = current

                // Tự động kết nối nếu đang tìm kiếm
                if (_connectedBike.value == null && (_state.value == BikeConnectionState.SCANNING || _state.value == BikeConnectionState.DISCONNECTED)) {
                    connect(bike)
                }
            } else {
                current[existingIdx] = bike
                _discoveredBikes.value = current
            }
        }
    }

    fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || bluetoothAdapter?.isEnabled != true) {
            log("Bluetooth chưa được bật")
            _state.value = BikeConnectionState.ERROR
            return
        }

        _state.value = BikeConnectionState.SCANNING
        _discoveredBikes.value = emptyList()
        log("Bắt đầu quét BLE tìm xe Yamaha...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CLUSTER_LINK_SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(STREET_CROSS_SERVICE_UUID)).build(),
            ScanFilter.Builder().build() // fallback quét tất cả và lọc qua isYamahaBikeCCU
        )

        scanner.startScan(filters, settings, leScanCallback)
    }

    fun stopScanning() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        if (_state.value == BikeConnectionState.SCANNING) {
            _state.value = BikeConnectionState.DISCONNECTED
        }
    }

    // =========================================================================
    // KẾT NỐI & QUẢN LÝ GATT
    // =========================================================================

    fun connect(device: BikeDevice) {
        stopScanning()
        _state.value = BikeConnectionState.CONNECTING
        _connectedBike.value = device
        log("Đang kết nối tới xe ${device.name} (${device.address})...")

        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        if (bluetoothDevice == null) {
            log("✗ Không tìm thấy thiết bị Bluetooth với địa chỉ ${device.address}")
            _state.value = BikeConnectionState.DISCONNECTED
            _connectedBike.value = null
            startScanning()
            return
        }

        bluetoothGatt = bluetoothDevice.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )

        // Đồng thời khởi chạy kết nối SPP RFCOMM cho kênh Stream màn hình xe
        connectSppChannel(bluetoothDevice)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceName = gatt?.device?.name ?: "Xe Yamaha"
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                log("✓ Đã kết nối BLE tầng liên kết với $deviceName, đang yêu cầu MTU 512...")
                _state.value = BikeConnectionState.CONNECTING
                // Yêu cầu MTU tối đa để stream ảnh JPEG mượt mà
                gatt?.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Xe ngắt kết nối BLE (status=$status)")
                handleDisconnection()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            log("Đã cập nhật MTU: $mtu (status=$status), bắt đầu khám phá dịch vụ CCU...")
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                log("✗ Khám phá dịch vụ thất bại (status=$status)")
                return
            }

            log("Đã tìm thấy ${gatt.services.size} dịch vụ trên xe")
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    val isWritable = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                    if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ) {
                        enableCharacteristicNotification(gatt, char)
                    }

                    if (char.uuid == DDT_WRITE_CHAR_UUID && isWritable) {
                        ddtWriteChar = char
                        log("  ✓ ĐÃ CHỌN CỔNG GHI TX: ${char.uuid}")
                    }
                    if (char.uuid == DDT_NOTIFY_CHAR_UUID) {
                        ddtNotifyChar = char
                        log("  ✓ ĐÃ CHỌN CỔNG NHẬN RX: ${char.uuid}")
                    }
                    if (char.uuid == TELEMETRY_CHAR_UUID) {
                        telemetryChar = char
                        log("  ✓ ĐÃ CHỌN CỔNG TELEMETRY: ${char.uuid}")
                    }
                }
            }

            if (ddtWriteChar != null) {
                sendAuthenticationV2()
            } else if (ddtNotifyChar != null || telemetryChar != null) {
                _state.value = BikeConnectionState.CONNECTED
                streetCross.displaySpec.isReadyForStreaming = true
                onBikeConnectedSuccessfully()
            } else {
                log("✗ Thiết bị không có đặc tính CCU Yamaha. Hủy kết nối.")
                disconnect()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (_state.value == BikeConnectionState.AUTHENTICATING && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = BikeConnectionState.CONNECTED
                streetCross.displaySpec.isReadyForStreaming = true
                onBikeConnectedSuccessfully()
                log("✓ Đã xác thực CCU thành công!")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
            handleIncomingGattData(characteristic.uuid, data)
        }
    }

    private fun enableCharacteristicNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleIncomingGattData(uuid: UUID, data: ByteArray) {
        if (_state.value == BikeConnectionState.AUTHENTICATING) {
            _state.value = BikeConnectionState.CONNECTED
            streetCross.displaySpec.isReadyForStreaming = true
            onBikeConnectedSuccessfully()
            log("✓ Xe đã phản hồi xác thực! Chuyển sang trạng thái Đã kết nối.")
        }

        if (uuid == DDT_NOTIFY_CHAR_UUID || uuid == DDT_WRITE_CHAR_UUID) {
            val response = streetCross.processIncomingData(data)
            if (response != null) {
                writeDdtData(response)
            }
        } else if (uuid == TELEMETRY_CHAR_UUID) {
            parseTelemetry(data)
        }
    }

    private fun sendAuthenticationV2() {
        val char = ddtWriteChar ?: return
        if (_state.value == BikeConnectionState.AUTHENTICATING ||
            _state.value == BikeConnectionState.CONNECTED ||
            _state.value == BikeConnectionState.STREAMING
        ) return

        _state.value = BikeConnectionState.AUTHENTICATING
        log("Gửi gói AuthenticationV2 lên xe qua cổng TX: ${char.uuid}...")

        val appIdBytes = "NConnectAndroid".toByteArray(Charsets.UTF_8)
        val authPacket = ByteArray(4 + appIdBytes.size)
        authPacket[0] = 0x02
        authPacket[1] = 0x01
        authPacket[2] = 0x00
        authPacket[3] = 0x10
        System.arraycopy(appIdBytes, 0, authPacket, 4, appIdBytes.size)

        writeDdtData(authPacket)

        scope.launch {
            delay(1200)
            if (_state.value == BikeConnectionState.AUTHENTICATING && bluetoothGatt != null) {
                log("✓ Hoàn tất xác thực CCU! Chuyển sang trạng thái Đã kết nối.")
                _state.value = BikeConnectionState.CONNECTED
                streetCross.displaySpec.isReadyForStreaming = true
                onBikeConnectedSuccessfully()
            }
        }
    }

    private fun writeDdtData(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = ddtWriteChar ?: return
        char.value = bytes
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
    }

    // =========================================================================
    // KÊNH BLUETOOTH SPP / RFCOMM CHO GARMIN STREETCROSS WIRE STREAMING
    // =========================================================================

    private fun connectSppChannel(device: BluetoothDevice) {
        sppJob?.cancel()
        sppJob = scope.launch(Dispatchers.IO) {
            try {
                log("Đang mở kênh SPP RFCOMM với màn hình xe (${device.address})...")
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                sppSocket = socket
                sppInputStream = socket.inputStream
                sppOutputStream = socket.outputStream
                log("✓ Kênh SPP RFCOMM đã kết nối thành công!")
                _state.value = BikeConnectionState.STREAMING
                streetCross.displaySpec.isReadyForStreaming = true
                onBikeConnectedSuccessfully()

                // Khởi động luồng đọc NaviLite frames liên tục
                runNaviLiteReader()
            } catch (e: Exception) {
                log("Kênh SPP không khả dụng, sử dụng kênh BLE GATT dự phòng.")
            }
        }
    }

    private suspend fun runNaviLiteReader() = withContext(Dispatchers.IO) {
        val input = sppInputStream ?: return@withContext
        val buffer = ByteArray(4096)

        while (sppSocket?.isConnected == true) {
            try {
                val bytesRead = input.read(buffer)
                if (bytesRead > 0) {
                    val frameBytes = buffer.copyOf(bytesRead)
                    val frame = parseNaviFrameFromBytes(frameBytes) ?: continue

                    // Xử lý gói tin theo Service ID
                    handleNaviLiteService(frame)
                }
            } catch (e: IOException) {
                break
            }
        }
    }

    private fun handleNaviLiteService(f: NaviFrame) {
        when (f.svc) {
            80 -> Unit // Heartbeat
            65 -> Unit // Idle poll
            66 -> {
                log("✓ Xe gửi svc 66! Gửi phản hồi xác thực svc 81 + svc 33")
                writeWireData(NaviLite.frame(6, 81, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 33, 1, NaviLite.hexB("1c07000100000000")))
            }
            83 -> {
                val ccuPart = NaviLite.partNumber(f.payload)
                log("✓ Mã CCU xe: $ccuPart → Gửi phản hồi echo svc 84 + setup burst")
                writeWireData(NaviLite.frame(6, 84, 1, NaviLite.secDataAckPayload(f.payload)))

                // Setup burst chuẩn Garmin StreetCross
                val currentRoad = if (lastSentRoadName.isNotEmpty() && lastSentRoadName != "MyNMAX") lastSentRoadName else "YAMAHA"
                val roadBytes = currentRoad.take(64).toByteArray(Charsets.UTF_8)
                val roadPayload = ByteArray(4 + roadBytes.size).apply {
                    this[0] = 0x07
                    this[1] = 0x1A
                    this[2] = (roadBytes.size and 0xFF).toByte()
                    this[3] = 0x00
                    System.arraycopy(roadBytes, 0, this, 4, roadBytes.size)
                }
                val s4Init = ByteArray(8 + roadBytes.size).apply {
                    this[0] = 1 // icon thẳng
                    this[1] = 0; this[2] = 0; this[3] = 0; this[4] = 0
                    this[5] = 1 // unit len
                    this[6] = roadBytes.size.toByte()
                    this[7] = 0x6D // 'm'
                    System.arraycopy(roadBytes, 0, this, 8, roadBytes.size)
                }

                writeWireData(NaviLite.frame(6, 2, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 31, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 10, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 11, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 13, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 12, 0, byteArrayOf(0, 0)))
                writeWireData(NaviLite.frame(6, 3, 1, roadBytes))
                writeWireData(NaviLite.frame(6, 4, 1, s4Init))
                writeWireData(NaviLite.frame(6, 14, 1, roadPayload))
                writeWireData(NaviLite.frame(6, 17, 1, NaviLite.hexB("00000000036d7068")))
                writeWireData(NaviLite.frame(6, 13, 0, byteArrayOf(1, 0)))
                writeWireData(NaviLite.frame(6, 12, 0, byteArrayOf(1, 0)))

                _state.value = BikeConnectionState.STREAMING
                streetCross.displaySpec.isReadyForStreaming = true
                onBikeConnectedSuccessfully()
                log("🎉 BẮT TAY NAVILITE THÀNH CÔNG! Màn hình TFT đã sẵn sàng nhận hình ảnh bản đồ!")
            }
            51, 57 -> {
                log("Phím cùm xe: Phóng to (+) / Zoom In (mã svc ${f.svc})")
                // Gửi ACK Service 14 ngay lập tức để giải phóng khóa phím của xe
                writeWireData(NaviLite.frame(6, 14, 1, byteArrayOf(0x07, 0x1A, 0x00, 0x00)))
                scope.launch(Dispatchers.Main) { onZoomIn?.invoke() }
            }
            52, 58 -> {
                log("Phím cùm xe: Thu nhỏ (-) / Zoom Out (mã svc ${f.svc})")
                writeWireData(NaviLite.frame(6, 14, 1, byteArrayOf(0x07, 0x1A, 0x00, 0x00)))
                scope.launch(Dispatchers.Main) { onZoomOut?.invoke() }
            }
            53 -> {
                log("Menu đồng hồ xe: Lệnh 53 (Về nhà / Go home)")
                writeWireData(NaviLite.frame(6, 14, 1, byteArrayOf(0x07, 0x1A, 0x00, 0x00)))
                scope.launch(Dispatchers.Main) { onGoHome?.invoke() }
            }
            54 -> {
                log("Menu đồng hồ xe: Lệnh 54 (Đi làm / Go to work)")
                writeWireData(NaviLite.frame(6, 14, 1, byteArrayOf(0x07, 0x1A, 0x00, 0x00)))
                scope.launch(Dispatchers.Main) { onGoToWork?.invoke() }
            }
            49 -> {
                log("Phím cùm xe: Dừng lộ trình dẫn đường (49)")
                writeWireData(NaviLite.frame(6, 14, 1, byteArrayOf(0x07, 0x1A, 0x00, 0x00)))
                scope.launch(Dispatchers.Main) { onStopRoute?.invoke() }
            }
            55 -> {
                val sub = if (f.payload.isNotEmpty()) f.payload[0].toInt() and 0xFF else 1
                when (sub) {
                    3 -> scope.launch(Dispatchers.Main) { onOpenFavorites?.invoke() }
                    4 -> scope.launch(Dispatchers.Main) { onFindGasStation?.invoke() }
                    else -> {
                        _state.value = BikeConnectionState.STREAMING
                        streetCross.displaySpec.isReadyForStreaming = true
                    }
                }
            }
            56 -> {
                log("Menu xe: Tạm dừng nhận hình (56) → Trả 20")
                writeWireData(NaviLite.frame(6, 20, 0, byteArrayOf(0, 0)))
            }
            48 -> {
                val itemIndex = if (f.payload.isNotEmpty()) f.payload[0].toInt() and 0xFF else 0
                val listId = if (f.payload.size > 1) f.payload[1].toInt() and 0xFF else 0
                scope.launch(Dispatchers.Main) { onSelectPoi?.invoke(itemIndex, listId) }
            }
        }
    }

    private fun parseNaviFrameFromBytes(data: ByteArray): NaviFrame? {
        if (data.size < 16) return null
        var start = 0
        while (start + 4 <= data.size) {
            if (data[start] == 0x6e.toByte() && data[start + 1] == 0x41.toByte() &&
                data[start + 2] == 0x6c.toByte() && data[start + 3] == 0x40.toByte()
            ) {
                break
            }
            start++
        }
        if (start + 16 > data.size) return null

        val svc = data[start + 6].toInt() and 0xFF
        val size = (data[start + 7].toInt() and 0xFF) or
                ((data[start + 8].toInt() and 0xFF) shl 8) or
                ((data[start + 9].toInt() and 0xFF) shl 16) or
                ((data[start + 10].toInt() and 0xFF) shl 24)

        val payload = if (size > 0 && start + 16 + size <= data.size) {
            data.copyOfRange(start + 16, start + 16 + size)
        } else {
            ByteArray(0)
        }
        return NaviFrame(svc, payload)
    }

    fun writeWireData(data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                sppOutputStream?.let { out ->
                    out.write(data)
                    out.flush()
                    return@launch
                }
            } catch (_: Exception) {}

            // Fallback gửi qua BLE DDT Write
            writeDdtData(data)
        }
    }

    // =========================================================================
    // STREAM HÌNH ẢNH LÊN MÀN HÌNH TFT
    // =========================================================================

    fun sendLiveMapFrameToTFT(jpegData: ByteArray) {
        // 1. Ưu tiên kênh SPP RFCOMM (chuẩn Garmin StreetCross wire format)
        if (sppOutputStream != null) {
            val payload = ByteArray(3 + jpegData.size)
            payload[0] = 3
            payload[1] = (naviSeq and 0xFF).toByte()
            payload[2] = ((naviSeq shr 8) and 0xFF).toByte()
            System.arraycopy(jpegData, 0, payload, 3, jpegData.size)
            naviSeq++

            val wirePacket = NaviLite.frame(6, 0, 1, payload)
            writeWireData(wirePacket)
            return
        }

        // 2. Kênh BLE GATT dự phòng (Gửi cắt nhỏ qua cổng DDT TX B606...)
        if (ddtWriteChar != null && (_state.value == BikeConnectionState.STREAMING || _state.value == BikeConnectionState.CONNECTED)) {
            val packets = streetCross.encodeJPEGFrameForTFT(jpegData)
            for (packet in packets) {
                writeDdtData(packet)
            }
        }
    }

    // =========================================================================
    // CẬP NHẬT TÊN ĐƯỜNG & ETA LÊN FOOTER ĐỒNG HỒ XE (TỔ HỢP 5 GÓI CHUẨN)
    // =========================================================================

    fun updateCurrentRoadNameOnTFT(
        rawRoadName: String,
        distanceMeters: Double = 0.0,
        maneuverIcon: Int = 0,
        etaText: String = ""
    ) {
        var cleanRoad = cleanVietnameseForGarmin(rawRoadName).uppercase(Locale.ROOT)
        if (cleanRoad.isEmpty()) {
            cleanRoad = "YAMAHA"
        }

        var fullFooter = cleanRoad
        if (etaText.isNotEmpty() && !cleanRoad.contains(" - ")) {
            val cleanEta = cleanVietnameseForGarmin(etaText).uppercase(Locale.ROOT)
            if (cleanEta.isNotEmpty()) {
                fullFooter = "$cleanRoad - $cleanEta"
            }
        }
        lastSentRoadName = fullFooter
        _currentRoadNameOnTFT.value = fullFooter

        val roadBytes = fullFooter.take(128).toByteArray(Charsets.UTF_8)

        // 1. Service 2 (PDT 0): Bật chế độ dẫn đường Active Navigation [1, 0]
        writeWireData(NaviLite.frame(6, 2, 0, byteArrayOf(1, 0)))

        // 2. Service 3 (PDT 1): Chuẩn Lo3/c;->f - Gửi trực tiếp chuỗi tên đường
        writeWireData(NaviLite.frame(6, 3, 1, roadBytes))

        // 3. Service 4 (PDT 1): Chuẩn Lm3/r;->l (Mũi tên + Cự ly float LE + Đơn vị + Tên đường)
        val unitStr = if (distanceMeters >= 1000.0) "km" else "m"
        val distVal = if (distanceMeters >= 1000.0) (distanceMeters / 1000.0).toFloat() else distanceMeters.toFloat()
        val unitBytes = unitStr.toByteArray(Charsets.UTF_8)

        val s4Buf = ByteBuffer.allocate(6 + unitBytes.size + roadBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        val iconByte = if (maneuverIcon == 0) 1 else maneuverIcon
        s4Buf.put(iconByte.toByte())
        s4Buf.putFloat(distVal)
        s4Buf.put(unitBytes.size.toByte())
        s4Buf.put(roadBytes.size.toByte())
        s4Buf.put(unitBytes)
        s4Buf.put(roadBytes)
        writeWireData(NaviLite.frame(6, 4, 1, s4Buf.array()))

        // 4. Service 14 (PDT 1): Chuẩn Lo3/c;->j - Lệnh Sub 0x1A hiển thị tên đường
        val roadPayload = ByteArray(4 + roadBytes.size)
        roadPayload[0] = 0x07
        roadPayload[1] = 0x1A
        roadPayload[2] = (roadBytes.size and 0xFF).toByte()
        roadPayload[3] = 0x00
        System.arraycopy(roadBytes, 0, roadPayload, 4, roadBytes.size)
        writeWireData(NaviLite.frame(6, 14, 1, roadPayload))

        // 5. Service 2 (PDT 1): Giờ đến ETA trong ngày
        val calendar = Calendar.currentOrNew()
        val currentMinuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val etaMin = minOf(1439, currentMinuteOfDay)
        val arrivePayload = byteArrayOf(0x03, (etaMin and 0xFF).toByte(), ((etaMin shr 8) and 0xFF).toByte(), 0x00)
        writeWireData(NaviLite.frame(6, 2, 1, arrivePayload))
    }

    fun stopNavigationOnTFT() {
        log("Đồng hồ xe: Tắt khung dẫn đường (Service 2 = [0, 0])")
        writeWireData(NaviLite.frame(6, 2, 0, byteArrayOf(0, 0)))
        lastSentRoadName = ""
        _currentRoadNameOnTFT.value = ""
    }

    // =========================================================================
    // PARSING TELEMETRY & NGẮT KẾT NỐI
    // =========================================================================

    private fun parseTelemetry(data: ByteArray) {
        if (data.size < 2) return

        // 1. Bắt tay ECU (Handshake ACK 309 -> 310)
        if ((data[0] == 0x01.toByte() && data[1] == 0x35.toByte()) ||
            (data[0] == 0x35.toByte() && data[1] == 0x01.toByte())
        ) {
            val sub = if (data.size > 2) data[2] else 0x00.toByte()
            log("🤝 Bắt tay ECU: Xe gửi mã 309 (sub=$sub) -> Phản hồi mã 310")
            writeDdtData(byteArrayOf(0x36.toByte(), 0x01.toByte(), sub))
            return
        }

        // 2. Tín hiệu tắt khóa điện xe từ ECU (Command 312 = 0x0138)
        if ((data[0] == 0x01.toByte() && data[1] == 0x38.toByte()) ||
            (data[0] == 0x38.toByte() && data[1] == 0x01.toByte())
        ) {
            log("🛵 [ECU] Xe phát tín hiệu tắt khóa điện (Command 312)")
            onBikeDisconnected?.invoke()
            return
        }

        // Đọc thông số tốc độ, điện áp nếu có trong gói telemetry
        if (data.size >= 8) {
            val speed = ((data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)) / 10.0
            val voltage = ((data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)) / 10.0
            if (speed in 0.0..180.0 && voltage in 8.0..16.0) {
                _telemetry.value = _telemetry.value.copy(
                    speedKmh = speed,
                    batteryVoltage = voltage
                )
            }
        }
    }

    private fun onBikeConnectedSuccessfully() {
        wasConnectedToBike = true
        onBikeConnected?.invoke()
    }

    private fun handleDisconnection() {
        _state.value = BikeConnectionState.DISCONNECTED
        _connectedBike.value = null
        bluetoothGatt?.close()
        bluetoothGatt = null
        ddtWriteChar = null
        ddtNotifyChar = null
        telemetryChar = null
        streetCross.reset()

        try {
            sppSocket?.close()
        } catch (_: Exception) {}
        sppSocket = null
        sppInputStream = null
        sppOutputStream = null
        sppJob?.cancel()

        onBikeDisconnected?.invoke()

        // Tự động quét lại sau 3s
        scope.launch {
            delay(3000)
            if (_connectedBike.value == null) {
                startScanning()
            }
        }
    }

    fun disconnect() {
        handleDisconnection()
        log("Đã ngắt kết nối với xe")
    }

    /**
     * Chuẩn hoá chuỗi tiếng Việt có dấu thành chuỗi ASCII hoa không dấu (32..126) cho màn hình xe
     */
    fun cleanVietnameseForGarmin(str: String): String {
        var clean = str
            .replace("đ", "d")
            .replace("Đ", "D")

        clean = Normalizer.normalize(clean, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")

        if (clean.startsWith("Duong ", ignoreCase = true)) clean = clean.substring(6)
        if (clean.startsWith("Pho ", ignoreCase = true)) clean = clean.substring(4)
        if (clean.startsWith("Hem ", ignoreCase = true)) clean = clean.substring(4)

        return clean.filter { it.code in 32..126 }.trim()
    }
}

private fun Calendar.Companion.currentOrNew(): Calendar = Calendar.getInstance()
