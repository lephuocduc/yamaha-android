package vn.tftlink.service.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
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
import kotlinx.coroutines.withContext
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

        fun getDeviceTypeString(type: Int?): String {
            return when (type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic (BR/EDR)"
                BluetoothDevice.DEVICE_TYPE_LE -> "BLE (Low Energy)"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode (BR/EDR/LE)"
                BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "Không rõ (Unknown)"
                null -> "N/A"
                else -> "Type=$type"
            }
        }

        fun getPairingVariantString(variant: Int): String {
            return when (variant) {
                0 -> "PIN (Nhập mã PIN)"
                1 -> "PASSKEY (Nhập mật mã số)"
                2 -> "PASSKEY_CONFIRMATION (So khớp mã 6 số hiển thị trên xe & máy)"
                3 -> "CONSENT (Hộp thoại đồng ý kết nối)"
                4 -> "DISPLAY_PASSKEY (Hiển thị passkey)"
                5 -> "DISPLAY_PIN (Hiển thị mã PIN)"
                6 -> "OOB_CONSENT (Xác thực ngoài băng tần OOB)"
                else -> "Variant=$variant"
            }
        }

        fun getUnbondReasonString(reason: Int): String {
            return when (reason) {
                1 -> "AUTH_FAILED (Mã PIN hoặc khóa bảo mật không khớp)"
                2 -> "AUTH_REJECTED (Bị từ chối ghép đôi - Xe hoặc người dùng đã từ chối. Trên xe có thể cần xóa liên kết điện thoại cũ trong Menu Meter)"
                3 -> "AUTH_CANCELED (Tiến trình ghép đôi bị hủy)"
                4 -> "REMOTE_DEVICE_DOWN (Mất tín hiệu hoặc xe đã tắt nguồn/khóa điện)"
                5 -> "DISCOVERY_IN_PROGRESS (Đang quét Bluetooth nên xung đột phần cứng)"
                6 -> "AUTH_TIMEOUT (Hết thời gian chờ phản hồi ghép đôi)"
                7 -> "REPEATED_ATTEMPTS (Thử ghép đôi quá nhiều lần thất bại, tạm thời bị khóa)"
                8 -> "REMOTE_VALIDATION_FAILURE (Xác thực thiết bị từ xa thất bại)"
                9 -> "PAIRING_VARIANT_NOT_SUPPORTED (Kiểu xác thực không hỗ trợ)"
                10 -> "SMP_SECURITY_FAILED (Lỗi trao đổi bảo mật BLE SMP)"
                11 -> "SMP_KEY_DIST_FAILED (Lỗi phân phối link key SMP)"
                else -> if (reason != -1) "Mã unbond reason=$reason" else "Không có mã lỗi"
            }
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

    private val _lastConnectionError = MutableStateFlow<String?>(null)
    val lastConnectionError: StateFlow<String?> = _lastConnectionError.asStateFlow()

    fun clearConnectionError() {
        _lastConnectionError.value = null
    }

    fun getBluetoothDevice(address: String): BluetoothDevice? {
        return bluetoothAdapter?.getRemoteDevice(address)
    }

    /**
     * Tìm thiết bị Classic Bluetooth (BR/EDR) đã ghép đôi trong hệ thống
     * khớp với tên hoặc địa chỉ của xe Yamaha CCU.
     */
    fun findMatchingClassicBondedDevice(name: String, bleAddress: String): BluetoothDevice? {
        val bonded = bluetoothAdapter?.bondedDevices ?: return null
        if (bonded.isEmpty()) return null

        // 1. So khớp trực tiếp địa chỉ MAC nếu đã trùng
        bonded.find { it.address.equals(bleAddress, ignoreCase = true) }?.let { return it }

        val cleanName = name.trim()
        if (cleanName.isEmpty()) return null

        // 2. So khớp tên đầy đủ (ví dụ "YCCU_0006030100D74D")
        bonded.find { it.name?.equals(cleanName, ignoreCase = true) == true }?.let { return it }

        // 3. So khớp linh hoạt: chuẩn hoá dấu gạch ngang/dưới và hậu tố serial của CCU
        val normalizedTarget = cleanName.replace("-", "_").lowercase(Locale.ROOT)
        for (b in bonded) {
            val bName = (b.name ?: "").trim().replace("-", "_").lowercase(Locale.ROOT)
            if (bName.isEmpty()) continue

            if (bName == normalizedTarget) return b
            if (normalizedTarget.startsWith("yccu_") && bName.startsWith("yccu_")) {
                val targetSuffix = normalizedTarget.removePrefix("yccu_")
                val bSuffix = bName.removePrefix("yccu_")
                if (targetSuffix.contains(bSuffix) || bSuffix.contains(targetSuffix)) return b
            }
            if (normalizedTarget.startsWith("ccu_") && bName.startsWith("ccu_")) {
                val targetSuffix = normalizedTarget.removePrefix("ccu_")
                val bSuffix = bName.removePrefix("ccu_")
                if (targetSuffix.contains(bSuffix) || bSuffix.contains(targetSuffix)) return b
            }
        }
        return null
    }

    fun unpairBike(device: BikeDevice): Boolean {
        val classicDev = findMatchingClassicBondedDevice(device.name, device.address)
        val targetDevice = classicDev ?: bluetoothAdapter?.getRemoteDevice(device.address) ?: return false
        return try {
            val method = targetDevice.javaClass.getMethod("removeBond")
            val success = method.invoke(targetDevice) as? Boolean ?: false
            log("Đã gọi removeBond() cho xe ${device.name} (${targetDevice.address}): kết quả = $success")
            val current = _discoveredBikes.value.toMutableList()
            val idx = current.indexOfFirst { it.address.equals(device.address, ignoreCase = true) }
            if (idx != -1) {
                current[idx] = current[idx].copy(isPaired = false, classicAddress = null)
                _discoveredBikes.value = current
            }
            _lastConnectionError.value = "Đã hủy ghép đôi ${device.name}. Bây giờ hãy vào menu trên xe để xóa điện thoại cũ trước khi kết nối lại."
            success
        } catch (e: Exception) {
            log("Lỗi khi gọi removeBond: ${e.message}")
            false
        }
    }

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

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val devName = device?.name ?: device?.address ?: "Thiết bị"
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val variantStr = getPairingVariantString(variant)
                    val pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    val keyStr = if (pairingKey != -1) String.format(Locale.ROOT, "%06d", pairingKey) else "N/A"
                    val isBike = device != null && (isYamahaBikeCCU(devName, emptyList()) ||
                            _discoveredBikes.value.any { it.address.equals(device.address, ignoreCase = true) })
                    val bikeTag = if (isBike) " ➔ [XE MÁY YAMAHA CCU]" else ""
                    log("🔔 YÊU CẦU GHÉP ĐÔI (ACTION_PAIRING_REQUEST): $devName (MAC: ${device?.address}) | Mã: $keyStr | Kiểu: $variantStr$bikeTag")
                    if (isBike) {
                        _lastConnectionError.value = "Xe yêu cầu ghép đôi (Mã xác thực: $keyStr). Hãy bấm Đồng ý/Ghép đôi trên màn hình điện thoại & xe!"
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val devName = device.name ?: device.address
                    val isBike = isYamahaBikeCCU(devName, emptyList()) ||
                            _discoveredBikes.value.any { it.address.equals(device.address, ignoreCase = true) }
                    val bikeTag = if (isBike) " ➔ [XE MÁY YAMAHA CCU]" else ""
                    log("🔗 [ACL LINK] Đã thiết lập kết nối phần cứng Bluetooth với: $devName (${device.address})$bikeTag")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val devName = device.name ?: device.address
                    val isBike = isYamahaBikeCCU(devName, emptyList()) ||
                            _discoveredBikes.value.any { it.address.equals(device.address, ignoreCase = true) }
                    val bikeTag = if (isBike) " ➔ [XE MÁY YAMAHA CCU]" else ""
                    log("💔 [ACL LINK] Đã ngắt kết nối phần cứng Bluetooth với: $devName (${device.address})$bikeTag")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val stateStr = when (state) {
                        BluetoothAdapter.STATE_OFF -> "TẮT (STATE_OFF)"
                        BluetoothAdapter.STATE_TURNING_ON -> "Đang bật... (STATE_TURNING_ON)"
                        BluetoothAdapter.STATE_ON -> "BẬT (STATE_ON)"
                        BluetoothAdapter.STATE_TURNING_OFF -> "Đang tắt... (STATE_TURNING_OFF)"
                        else -> "State=$state"
                    }
                    log("📶 Bluetooth Adapter máy chuyển trạng thái: $stateStr")
                    if (state == BluetoothAdapter.STATE_ON) {
                        scope.launch {
                            delay(1000)
                            if (_state.value == BikeConnectionState.DISCONNECTED && _connectedBike.value == null) {
                                startScanning()
                            }
                        }
                    } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        handleDisconnection()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                    val devName = device.name ?: device.address
                    val devType = getDeviceTypeString(device.type)

                    val isBike = isYamahaBikeCCU(devName, emptyList()) ||
                            _discoveredBikes.value.any { it.address.equals(device.address, ignoreCase = true) }

                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            val msg = "Đang ghép đôi Bluetooth với: $devName ($devType). Vui lòng nhấn Đồng ý trên xe và điện thoại..."
                            log("⏳ $msg")
                            if (isBike) {
                                _lastConnectionError.value = msg
                                stopScanning()
                            }
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            val msg = "✓ Ghép đôi Bluetooth THÀNH CÔNG với: $devName (${device.address}) [$devType]!"
                            log(msg)
                            if (isBike) {
                                _lastConnectionError.value = null
                                val current = _discoveredBikes.value.toMutableList()
                                val idx = current.indexOfFirst {
                                    it.address.equals(device.address, ignoreCase = true) ||
                                    it.name.equals(devName, ignoreCase = true)
                                }
                                val updatedBike = if (idx != -1) {
                                    val b = current[idx].copy(isPaired = true, classicAddress = device.address)
                                    current[idx] = b
                                    b
                                } else {
                                    val b = BikeDevice(
                                        id = device.address,
                                        name = if (devName.isNotBlank()) devName else "Yamaha CCU",
                                        address = device.address,
                                        rssi = -60,
                                        isPaired = true,
                                        classicAddress = device.address
                                    )
                                    current.add(b)
                                    b
                                }
                                _discoveredBikes.value = current

                                if (_state.value == BikeConnectionState.CONNECTED || _state.value == BikeConnectionState.AUTHENTICATING) {
                                    log("⚡ Kích hoạt ngay kênh SPP RFCOMM tới xe vừa ghép đôi thành công ($devName - ${device.address})...")
                                    connectSppChannel(device)
                                } else {
                                    scope.launch {
                                        delay(2000)
                                        if (_connectedBike.value == null && (_state.value == BikeConnectionState.SCANNING || _state.value == BikeConnectionState.DISCONNECTED)) {
                                            log("⚡ Tự động kết nối tới xe vừa ghép đôi thành công: ${updatedBike.name}")
                                            connect(updatedBike)
                                        }
                                    }
                                }
                            }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            val reasonDesc = getUnbondReasonString(reason)
                            val transitionMsg = if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                "Ghép đôi Bluetooth thất bại với $devName (Lý do: $reasonDesc). Hãy thử ghép đôi lại hoặc xóa liên kết cũ trên xe."
                            } else {
                                "Đã hủy ghép đôi thiết bị: $devName (Lý do: $reasonDesc)"
                            }
                            log("⚠️ $transitionMsg")
                            if (isBike) {
                                _lastConnectionError.value = transitionMsg
                                val current = _discoveredBikes.value.toMutableList()
                                val idx = current.indexOfFirst {
                                    it.address.equals(device.address, ignoreCase = true) ||
                                    it.name.equals(devName, ignoreCase = true)
                                }
                                if (idx != -1) {
                                    current[idx] = current[idx].copy(isPaired = false, classicAddress = null)
                                    _discoveredBikes.value = current
                                }

                                scope.launch {
                                    delay(2000)
                                    if (_connectedBike.value == null && _state.value == BikeConnectionState.DISCONNECTED) {
                                        startScanning()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(bondStateReceiver, filter)
        } catch (e: Exception) {
            log("Lỗi đăng ký bondStateReceiver: ${e.message}")
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
        val rawName = device.name ?: result.scanRecord?.deviceName ?: ""
        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

        val name = if (rawName.isBlank()) {
            if (serviceUuids.contains(CLUSTER_LINK_SERVICE_UUID) || serviceUuids.contains(STREET_CROSS_SERVICE_UUID)) {
                "Yamaha CCU (${device.address.takeLast(5)})"
            } else {
                ""
            }
        } else {
            rawName
        }

        if (name.isEmpty()) return

        if (isYamahaBikeCCU(name, serviceUuids)) {
            val classicBonded = findMatchingClassicBondedDevice(name, device.address)
            val isBonded = (device.bondState == BluetoothDevice.BOND_BONDED) || (classicBonded != null)
            val isBonding = (device.bondState == BluetoothDevice.BOND_BONDING) || (classicBonded?.bondState == BluetoothDevice.BOND_BONDING)

            val bike = BikeDevice(
                id = device.address,
                name = name,
                address = device.address,
                rssi = result.rssi,
                isPaired = isBonded,
                classicAddress = classicBonded?.address
            )

            val current = _discoveredBikes.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.address.equals(bike.address, ignoreCase = true) }
            if (existingIdx == -1) {
                log("✓ Thấy xe Yamaha hợp lệ: $name (BLE: ${device.address}, Classic đã ghép: ${classicBonded?.address ?: "chưa"}), RSSI: ${result.rssi} dBm")
                current.add(bike)
                _discoveredBikes.value = current
            } else {
                current[existingIdx] = bike
                _discoveredBikes.value = current
            }

            // Nếu đang trong tiến trình ghép đôi (BOND_BONDING), tạm dừng quét BLE để tránh làm nghẽn sóng RF
            if (isBonding) {
                log("⏳ Xe $name đang ghép đôi (BOND_BONDING). Tạm dừng quét BLE để giải phóng sóng Bluetooth...")
                stopScanning()
                return
            }

            // Tự động kết nối nếu xe đã từng được ghép đôi và hiện tại chưa có xe nào kết nối
            if (isBonded && _connectedBike.value == null &&
                (_state.value == BikeConnectionState.SCANNING || _state.value == BikeConnectionState.DISCONNECTED)
            ) {
                log("⚡ Tự động kết nối tới xe đã ghép đôi: $name (${device.address})")
                connect(bike)
            }
        }
    }

    fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || bluetoothAdapter?.isEnabled != true) {
            log("❌ Bluetooth chưa được bật trên điện thoại. Vui lòng bật Bluetooth.")
            _state.value = BikeConnectionState.ERROR
            return
        }

        // Kiểm tra dịch vụ định vị GPS (Android 9 trở lên bắt buộc GPS để scan BLE)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (!isGpsEnabled) {
            log("⚠️ VỊ TRÍ (GPS) ĐANG TẮT! Trên Android 9, bắt buộc bật Vị trí trong Cài đặt nhanh thì mới quét được BLE.")
        }

        _state.value = BikeConnectionState.SCANNING

        // Lấy danh sách các thiết bị đã từng ghép đôi trong Cài đặt Bluetooth máy
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        val candidateBonded = bonded.filter { isYamahaBikeCCU(it.name ?: "", emptyList()) }
        val curList = mutableListOf<BikeDevice>()
        for (d in candidateBonded) {
            val bike = BikeDevice(
                id = d.address,
                name = d.name ?: "Yamaha CCU",
                address = d.address,
                rssi = -60,
                isPaired = true,
                classicAddress = d.address
            )
            curList.add(bike)
            log(" 🏍️ Xe Yamaha đã ghép đôi sẵn trong máy: ${bike.name} (${bike.address})")
        }
        _discoveredBikes.value = curList

        log("🔍 Bắt đầu quét BLE tìm xe Yamaha xung quanh (đã ghép đôi: ${candidateBonded.size} xe)...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
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

    private var connectionWatchdogJob: Job? = null

    private fun refreshDeviceCache(gatt: BluetoothGatt?): Boolean {
        if (gatt == null) return false
        return try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            (refreshMethod.invoke(gatt) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun safelyCloseGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        try {
            gatt.disconnect()
        } catch (e: Exception) {
            log("Lỗi khi disconnect GATT: ${e.message}")
        }
        try {
            refreshDeviceCache(gatt)
        } catch (_: Exception) {}
        try {
            gatt.close()
        } catch (e: Exception) {
            log("Lỗi khi close GATT: ${e.message}")
        }
    }

    fun connect(device: BikeDevice) {
        stopScanning()
        connectionWatchdogJob?.cancel()

        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        if (bluetoothDevice == null) {
            val err = "Không tìm thấy thiết bị Bluetooth với địa chỉ ${device.address}"
            log("✗ $err")
            _lastConnectionError.value = err
            _state.value = BikeConnectionState.ERROR
            _connectedBike.value = null
            startScanning()
            return
        }

        val classicBondedDevice = findMatchingClassicBondedDevice(device.name, device.address)
        val isClassicPaired = classicBondedDevice != null || (bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED)
        val devType = getDeviceTypeString(bluetoothDevice.type)
        log("Chi tiết thiết bị: ${device.name} [BLE MAC: ${device.address}, BondState=${bluetoothDevice.bondState}, Type=$devType, ClassicBonded=${classicBondedDevice?.address ?: "chưa"}]")

        // Kiểm tra nếu xe đang trong tiến trình ghép đôi từ Cài đặt Bluetooth máy
        if (bluetoothDevice.bondState == BluetoothDevice.BOND_BONDING || classicBondedDevice?.bondState == BluetoothDevice.BOND_BONDING) {
            val msg = "Xe ${device.name} đang trong quá trình ghép đôi. Vui lòng xác nhận trên màn hình xe và điện thoại..."
            log("⏳ $msg")
            _lastConnectionError.value = msg
            _state.value = BikeConnectionState.CONNECTING
            _connectedBike.value = device.copy(isPaired = isClassicPaired, classicAddress = classicBondedDevice?.address)
            return
        }

        _state.value = BikeConnectionState.CONNECTING
        _connectedBike.value = device.copy(isPaired = isClassicPaired, classicAddress = classicBondedDevice?.address)
        _lastConnectionError.value = null

        if (isClassicPaired) {
            log("Đang kết nối tới xe ${device.name} (BLE: ${device.address}, Classic: ${classicBondedDevice?.address ?: device.address})...")
        } else {
            log("Đang kết nối BLE GATT tới xe ${device.name} (${device.address}) [Chưa ghép đôi Classic]...")
        }

        // Trên Android: Giải phóng handle GATT cũ, đợi 350ms để Bluedroid stack ổn định trước khi connectGatt
        scope.launch {
            safelyCloseGatt(bluetoothGatt)
            bluetoothGatt = null
            delay(350)

            log("Bắt đầu mở kênh connectGatt (TRANSPORT_LE)...")
            bluetoothGatt = bluetoothDevice.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            // Watchdog 25 giây: Nếu sau 25s vẫn chưa kết nối được thì báo timeout
            connectionWatchdogJob = launch {
                delay(25000)
                if (_state.value == BikeConnectionState.CONNECTING && _connectedBike.value != null) {
                    val timeoutMsg = "Hết thời gian chờ kết nối với ${device.name} (Timeout 25s). Hãy bật khóa xe hoặc kiểm tra Bluetooth."
                    log("⚠️ $timeoutMsg")
                    _lastConnectionError.value = timeoutMsg
                    safelyCloseGatt(bluetoothGatt)
                    bluetoothGatt = null
                    handleDisconnection()
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceName = gatt?.device?.name ?: _connectedBike.value?.name ?: "Xe Yamaha"
            val statusDesc = when (status) {
                BluetoothGatt.GATT_SUCCESS -> "Thành công (0/GATT_SUCCESS)"
                133 -> "Lỗi 133 (0x85 GATT_ERROR: Android Bluedroid stack bị đầy hoặc từ chối kết nối. Gợi ý: Bật tắt lại Bluetooth điện thoại và bật lại khóa xe)"
                8 -> "Lỗi 8 (GATT_CONN_TIMEOUT: Hết thời gian chờ, xe quá xa hoặc chưa bật khóa điện)"
                19 -> "Lỗi 19 (GATT_CONN_TERMINATE_PEER_USER: Xe chủ động ngắt kết nối / đã tắt khóa điện)"
                22 -> "Lỗi 22 (GATT_CONN_TERMINATE_LOCAL_HOST: Điện thoại chủ động ngắt kết nối)"
                62 -> "Lỗi 62 (GATT_CONN_FAIL_ESTABLISH: Không thể thiết lập liên kết Link Layer)"
                137 -> "Lỗi 137 (GATT_INTERNAL_ERROR: Lỗi bộ nhớ đệm stack Bluetooth)"
                257 -> "Lỗi 257 (GATT_FAILURE: Thao tác GATT thất bại)"
                else -> "GATT status=$status (0x${Integer.toHexString(status)})"
            }

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectionWatchdogJob?.cancel()
                log("✓ Đã kết nối BLE tầng liên kết với $deviceName, đang yêu cầu MTU 512...")
                _state.value = BikeConnectionState.CONNECTING
                _lastConnectionError.value = null
                // Yêu cầu MTU tối đa để stream ảnh JPEG mượt mà
                gatt?.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionWatchdogJob?.cancel()
                safelyCloseGatt(gatt)
                if (bluetoothGatt == gatt) bluetoothGatt = null
                log("Xe ngắt kết nối BLE ($statusDesc)")
                if (_state.value == BikeConnectionState.CONNECTING || _state.value == BikeConnectionState.AUTHENTICATING) {
                    _lastConnectionError.value = "Kết nối với $deviceName bị ngắt ($statusDesc). Nếu xe báo Pairing Failed, hãy hủy ghép đôi trên cả 2 bên và thử lại."
                }
                handleDisconnection()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionWatchdogJob?.cancel()
                safelyCloseGatt(gatt)
                if (bluetoothGatt == gatt) bluetoothGatt = null
                log("✗ Kết nối BLE thất bại ($statusDesc)")
                _lastConnectionError.value = "Kết nối BLE thất bại: $statusDesc"
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

            log("Đã tìm thấy ${gatt.services.size} dịch vụ trên xe:")
            for (service in gatt.services) {
                val isCluster = service.uuid == CLUSTER_LINK_SERVICE_UUID
                val isStreet = service.uuid == STREET_CROSS_SERVICE_UUID
                val tag = if (isCluster) " [Yamaha ClusterLink]" else if (isStreet) " [StreetCross]" else ""
                log(" • Dịch vụ: ${service.uuid}$tag")

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
                log("✗ Thiết bị không có đặc tính CCU Yamaha mong muốn. Hủy kết nối.")
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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingGattData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { handleIncomingGattData(characteristic.uuid, it) }
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
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("Chưa thể mở kênh SPP vì thiết bị chưa hoàn tất ghép đôi (bondState=${device.bondState})")
            return
        }
        sppJob?.cancel()
        sppJob = scope.launch(Dispatchers.IO) {
            try {
                log("Đang mở kênh SPP RFCOMM với màn hình xe (${device.name ?: "Yamaha"} - ${device.address})...")
                val socket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    try {
                        device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    } catch (_: Exception) {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        m.invoke(device, 1) as BluetoothSocket
                    }
                }
                socket.connect()
                sppSocket = socket
                sppInputStream = socket.inputStream
                sppOutputStream = socket.outputStream
                log("✓ Kênh SPP RFCOMM đã kết nối thành công!")
                _state.value = BikeConnectionState.STREAMING
                streetCross.displaySpec.isReadyForStreaming = true

                // Khởi động luồng đọc NaviLite frames liên tục
                runNaviLiteReader()
            } catch (e: Exception) {
                log("Kênh SPP không khả dụng (${e.javaClass.simpleName}: ${e.message}), sử dụng kênh BLE GATT dự phòng.")
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

    fun sendJPEGFrame(jpegData: ByteArray) {
        sendLiveMapFrameToTFT(jpegData)
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

        val s4Buf = ByteBuffer.allocate(7 + unitBytes.size + roadBytes.size).order(ByteOrder.LITTLE_ENDIAN)
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
        val calendar = Calendar.getInstance()
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

    fun updateRoadNameOnTFT(name: String) {
        updateCurrentRoadNameOnTFT(name)
    }

    fun sendTurnByTurn(instruction: vn.tftlink.data.model.nav.NavigationInstruction) {
        updateCurrentRoadNameOnTFT(
            rawRoadName = if (instruction.nextRoadName.isNotBlank()) instruction.nextRoadName else instruction.detailedInstruction,
            distanceMeters = instruction.distanceMeters.toDouble(),
            maneuverIcon = instruction.direction.code,
            etaText = instruction.etaString
        )
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

        // Tìm thiết bị Classic Bluetooth tương ứng để mở kênh SPP RFCOMM cho Garmin StreetCross
        val curBike = _connectedBike.value
        val classicDev = curBike?.let { findMatchingClassicBondedDevice(it.name, it.address) }
            ?: curBike?.classicAddress?.let { bluetoothAdapter?.getRemoteDevice(it) }
            ?: curBike?.address?.let { addr ->
                val d = bluetoothAdapter?.getRemoteDevice(addr)
                if (d?.bondState == BluetoothDevice.BOND_BONDED) d else null
            }

        if (classicDev != null && classicDev.bondState == BluetoothDevice.BOND_BONDED) {
            if (sppSocket == null || sppSocket?.isConnected != true) {
                log("✓ Đã xác thực BLE CCU! Đang mở kênh SPP RFCOMM tới ${classicDev.name ?: "xe"} (${classicDev.address})...")
                connectSppChannel(classicDev)
            }
        } else {
            log("ℹ️ Đã kết nối BLE & Telemetry thành công! Xe chưa ghép đôi Classic trong Cài đặt máy nên tạm thời chưa mở kênh dẫn đường.")
        }
    }

    private fun handleDisconnection() {
        _state.value = BikeConnectionState.DISCONNECTED
        _connectedBike.value = null
        safelyCloseGatt(bluetoothGatt)
        bluetoothGatt = null
        ddtWriteChar = null
        ddtNotifyChar = null
        telemetryChar = null
        _telemetry.value = BikeTelemetry()
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

    fun startStreaming() {
        if (_state.value == BikeConnectionState.CONNECTED || _state.value == BikeConnectionState.AUTHENTICATING) {
            _state.value = BikeConnectionState.STREAMING
            log("Bắt đầu truyền luồng màn hình TFT")
        }
    }

    fun stopStreaming() {
        if (_state.value == BikeConnectionState.STREAMING) {
            _state.value = BikeConnectionState.CONNECTED
            log("Tạm dừng truyền luồng màn hình TFT")
        }
    }

    fun disconnect() {
        stopStreaming()
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
