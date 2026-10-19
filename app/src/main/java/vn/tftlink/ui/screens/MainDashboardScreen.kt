package vn.tftlink.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import vn.tftlink.data.model.bike.BikeConnectionState
import vn.tftlink.data.model.bike.BikeDevice
import vn.tftlink.data.model.location.SavedPlace
import vn.tftlink.ui.MainViewModel
import vn.tftlink.ui.theme.DarkBackground
import vn.tftlink.ui.theme.DarkSurface
import vn.tftlink.ui.theme.DarkSurfaceBorder
import vn.tftlink.ui.theme.DarkSurfaceCard
import vn.tftlink.ui.theme.NeonGreen
import vn.tftlink.ui.theme.NeonOrange
import vn.tftlink.ui.theme.NeonPurple
import vn.tftlink.ui.theme.NeonRed
import vn.tftlink.ui.theme.TextMuted
import vn.tftlink.ui.theme.TextPrimary
import vn.tftlink.ui.theme.TextSecondary
import vn.tftlink.ui.theme.YamahaCyan
import vn.tftlink.util.GeoUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    viewModel: MainViewModel,
    onNavigateToMapTab: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedBike by viewModel.connectedBike.collectAsState()
    val discoveredBikes by viewModel.discoveredBikes.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val currentRoad by viewModel.currentRoadNameOnTFT.collectAsState()
    val lastFrameBitmap by viewModel.lastFrameBitmap.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val zoomDesc by viewModel.zoomDescription.collectAsState()
    val parkedLoc by viewModel.parkedLocation.collectAsState()
    val homePlace by viewModel.homePlace.collectAsState()
    val workPlace by viewModel.workPlace.collectAsState()
    val favPlaces by viewModel.favoritePlaces.collectAsState()
    val currentLoc by viewModel.currentLocation.collectAsState()
    val arrivalMsg by viewModel.arrivalMessage.collectAsState()

    var showScanDialog by remember { mutableStateOf(false) }
    var showAddFavDialog by remember { mutableStateOf(false) }
    var showEditPlaceDialog by remember { mutableStateOf<String?>(null) } // "home" or "work"
    var showGasDialog by remember { mutableStateOf(false) }
    var showPairingGuideDialog by remember { mutableStateOf(false) }
    val lastConnectionError by viewModel.lastConnectionError.collectAsState()
    val nearbyGas by viewModel.nearbyGasStations.collectAsState()
    val isFetchingGas by viewModel.isFetchingGasStations.collectAsState()
    val gasError by viewModel.gasStationError.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MyNMAX",
                        color = TextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "NConnect Companion & TFT Stream",
                        color = YamahaCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(
                    onClick = {
                        if (connectionState == BikeConnectionState.DISCONNECTED || connectionState == BikeConnectionState.ERROR) {
                            viewModel.startScanning()
                            showScanDialog = true
                        } else {
                            viewModel.disconnect()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Quét lại",
                        tint = if (connectionState == BikeConnectionState.CONNECTED || connectionState == BikeConnectionState.STREAMING) NeonGreen else YamahaCyan
                    )
                }
            }
        }

        // Arrival message
        arrivalMsg?.let { msg ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF072412)),
                    border = BorderStroke(1.dp, NeonGreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ĐÃ ĐẾN NƠI!", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(msg, color = TextPrimary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { viewModel.clearArrivalMessage() }) {
                            Icon(Icons.Default.Close, contentDescription = "Đóng", tint = NeonGreen)
                        }
                    }
                }
            }
        }

        // 1. Connection Card
        item {
            ConnectionStatusCard(
                state = connectionState,
                connectedBike = connectedBike,
                isStreaming = isStreaming,
                lastConnectionError = lastConnectionError,
                onClearError = { viewModel.clearConnectionError() },
                onOpenGuide = { showPairingGuideDialog = true },
                onConnectClick = {
                    if (isStreaming) {
                        viewModel.stopStreaming()
                    } else if (connectionState == BikeConnectionState.CONNECTED || connectionState == BikeConnectionState.AUTHENTICATING) {
                        viewModel.startStreaming()
                    } else {
                        viewModel.startScanning()
                        showScanDialog = true
                    }
                }
            )
        }

        // 2. Telemetry Card
        item {
            TelemetryGridCard(
                telemetry = telemetry,
                currentRoad = currentRoad,
                isConnected = connectionState == BikeConnectionState.CONNECTED
            )
        }

        // 3. TFT Mirror Preview Card
        item {
            TFTMirrorPreviewCard(
                bitmap = lastFrameBitmap,
                isStreaming = isStreaming,
                isConnected = connectionState == BikeConnectionState.CONNECTED,
                currentRoad = currentRoad,
                zoomDescription = zoomDesc,
                onToggleStreaming = { viewModel.toggleStreaming() },
                onZoomIn = { viewModel.zoomIn() },
                onZoomOut = { viewModel.zoomOut() }
            )
        }

        // 4. Find My Bike (Parking Card)
        item {
            ParkingLocationCard(
                parked = parkedLoc,
                currentLocation = currentLoc,
                onNavigateToBike = {
                    parkedLoc?.let {
                        viewModel.startNavigationTo(it.coordinate, "Xe ${it.bikeName}")
                        onNavigateToMapTab()
                    }
                },
                onOpenGoogleMaps = {
                    parkedLoc?.let {
                        val uri = Uri.parse("google.navigation:q=${it.latitude},${it.longitude}&mode=w")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                },
                onClearParking = { viewModel.clearParkingLocation() }
            )
        }

        // 5. Quick Destinations (Home, Work, Gas)
        item {
            QuickActionsSection(
                homePlace = homePlace,
                workPlace = workPlace,
                onNavigateToHome = {
                    homePlace?.let {
                        viewModel.startNavigationTo(it.coordinate, it.name)
                        onNavigateToMapTab()
                    } ?: run { showEditPlaceDialog = "home" }
                },
                onNavigateToWork = {
                    workPlace?.let {
                        viewModel.startNavigationTo(it.coordinate, it.name)
                        onNavigateToMapTab()
                    } ?: run { showEditPlaceDialog = "work" }
                },
                onFindGas = {
                    showGasDialog = true
                    viewModel.fetchNearbyGasStations()
                },
                onEditHome = { showEditPlaceDialog = "home" },
                onEditWork = { showEditPlaceDialog = "work" }
            )
        }

        // 6. Favorite Places
        item {
            FavoritePlacesSection(
                favorites = favPlaces,
                onAddFavorite = { showAddFavDialog = true },
                onNavigateToFav = { fav ->
                    viewModel.startNavigationTo(fav.coordinate, fav.name)
                    onNavigateToMapTab()
                },
                onDeleteFav = { favId ->
                    viewModel.removeFavoritePlace(favId)
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Dialog quét thiết bị BLE CCU
    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = {
                showScanDialog = false
                viewModel.stopScanning()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = YamahaCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tìm xe Yamaha CCU", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        "Bật chìa khóa xe của bạn để CCU phát sóng Bluetooth.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    // Hiển thị trạng thái đang kết nối nếu có
                    if (connectionState == BikeConnectionState.CONNECTING) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(YamahaCyan.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = YamahaCyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Đang kết nối tới ${connectedBike?.name ?: "xe"}...",
                                color = YamahaCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Hiển thị thông báo lỗi nếu có
                    lastConnectionError?.let { err ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = NeonRed.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(err, color = Color(0xFFFF8A80), fontSize = 11.sp, lineHeight = 15.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "💡 Xem hướng dẫn sửa lỗi Pairing Failed",
                                    color = YamahaCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showPairingGuideDialog = true }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    if (discoveredBikes.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = YamahaCyan, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Đang dò tìm xe xung quanh...", color = YamahaCyan, fontSize = 13.sp)
                        }
                    } else {
                        discoveredBikes.forEach { bike ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                                border = BorderStroke(1.dp, if (bike.isPaired) YamahaCyan else DarkSurfaceBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, tint = if (bike.isPaired) YamahaCyan else TextSecondary)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(bike.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (bike.isPaired) "Đã ghép đôi" else "Chưa ghép đôi",
                                                color = if (bike.isPaired) YamahaCyan else TextMuted,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(" • ${bike.rssi} dBm", color = TextMuted, fontSize = 11.sp)
                                        }
                                    }

                                    if (bike.isPaired) {
                                        TextButton(
                                            onClick = { viewModel.unpairBike(bike) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Hủy ghép", color = TextMuted, fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(
                                            onClick = {
                                                viewModel.connectBike(bike)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Kết nối", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.openBluetoothSettings(context)
                                                },
                                                border = BorderStroke(1.dp, YamahaCyan),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Ghép đôi", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = {
                                                    viewModel.connectBike(bike)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Kết nối BLE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.openBluetoothSettings(context) }) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cài đặt Bluetooth", color = YamahaCyan, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showPairingGuideDialog = true }) {
                        Text("Hướng dẫn", color = TextSecondary, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        showScanDialog = false
                        viewModel.stopScanning()
                    }) {
                        Text("Đóng", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog hướng dẫn khắc phục lỗi Pairing Failed
    if (showPairingGuideDialog) {
        AlertDialog(
            onDismissRequest = { showPairingGuideDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = YamahaCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Khắc phục lỗi ghép đôi xe", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Khi xe báo \"Pairing Failed\" hoặc \"Waiting for approval\", đó là do bộ nhớ Bluetooth trên xe và điện thoại bị lệch khóa bảo mật cũ. Hãy làm theo 3 bước sau:",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = BorderStroke(1.dp, YamahaCyan.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Bước 1: Xóa kết nối cũ trên cả 2 bên", color = YamahaCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• Trên điện thoại: Nhấn nút \"Hủy ghép\" cạnh tên xe trong danh sách tìm kiếm của app (hoặc vào Cài đặt Bluetooth máy chọn Quên thiết bị / Hủy ghép đôi).\n• Trên đồng hồ xe: Dùng nút gạt/menu trên cùm tay lái vào Cài đặt xe -> Xóa điện thoại đã đăng ký (Delete/Remove Smartphone).", color = TextPrimary, fontSize = 11.sp)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = BorderStroke(1.dp, DarkSurfaceBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Bước 2: Khởi động lại CCU xe", color = NeonOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tắt chìa khóa xe, đợi 3 giây rồi bật lại khóa điện để CCU xóa sạch bộ nhớ tạm và phát sóng mới.", color = TextPrimary, fontSize = 11.sp)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Bước 3: Ghép đôi qua Cài đặt Bluetooth máy", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• Nhấn nút \"Mở Cài đặt Bluetooth\" bên dưới.\n• Trong danh sách thiết bị quét được, chọn tên xe Yamaha (YCCU_...).\n• Màn hình xe và điện thoại sẽ hiện mã số PIN: Nhấn ĐỒNG Ý / APPROVE trên cả điện thoại và xe.\n• Sau khi ghép đôi xong, quay lại app NConnect sẽ tự động kết nối và chiếu bản đồ!", color = TextPrimary, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.openBluetoothSettings(context)
                        },
                        border = BorderStroke(1.dp, YamahaCyan),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cài đặt Bluetooth", color = YamahaCyan, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { showPairingGuideDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Đã hiểu", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog lưu Nhà / Cơ quan: nhập địa chỉ thật -> chọn gợi ý để lấy toạ độ đúng
    showEditPlaceDialog?.let { type ->
        val existing = if (type == "home") homePlace else workPlace
        val defaultName = if (type == "home") "Nhà riêng" else "Cơ quan"
        var nameInput by remember(type) { mutableStateOf(existing?.name ?: defaultName) }
        var queryInput by remember(type) { mutableStateOf(existing?.address ?: "") }
        var picked by remember(type) { mutableStateOf<SavedPlace?>(existing) }
        val suggestions by viewModel.searchResults.collectAsState()
        val isSearchingAddr by viewModel.isSearching.collectAsState()

        // Chỉ tìm gợi ý khi người dùng đang gõ (chưa chọn kết quả nào)
        LaunchedEffect(queryInput, picked) {
            if (picked == null && queryInput.trim().length >= 3) {
                viewModel.searchPlaces(queryInput)
            } else {
                viewModel.searchPlaces("")
            }
        }

        fun closeDialog() {
            viewModel.searchPlaces("")
            showEditPlaceDialog = null
        }

        AlertDialog(
            onDismissRequest = { closeDialog() },
            title = {
                Text(if (type == "home") "Địa chỉ Nhà" else "Địa chỉ Cơ quan", color = TextPrimary)
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Tên hiển thị") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = {
                            queryInput = it
                            picked = null
                        },
                        label = { Text("Gõ địa chỉ để tìm (số nhà, đường, phường...)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (picked != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                String.format(java.util.Locale.US, "Đã xác định toạ độ: %.5f, %.5f", picked!!.latitude, picked!!.longitude),
                                color = NeonGreen,
                                fontSize = 11.sp
                            )
                        }
                    } else if (isSearchingAddr) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = YamahaCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Đang tìm địa chỉ...", color = TextSecondary, fontSize = 11.sp)
                        }
                    } else if (queryInput.trim().length >= 3 && suggestions.isEmpty()) {
                        Text("Không tìm thấy địa chỉ. Thử gõ ngắn hơn hoặc dùng vị trí hiện tại.", color = NeonOrange, fontSize = 11.sp)
                    } else if (queryInput.isBlank()) {
                        Text("Nhập địa chỉ rồi chọn một gợi ý bên dưới.", color = TextMuted, fontSize = 11.sp)
                    }

                    if (picked == null) {
                        suggestions.take(5).forEach { place ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .background(DarkSurfaceCard, RoundedCornerShape(8.dp))
                                    .clickable {
                                        picked = place
                                        queryInput = listOf(place.name, place.address)
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                            .joinToString(", ")
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(place.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(place.address, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            currentLoc?.let { loc ->
                                picked = SavedPlace(name = nameInput, address = "", latitude = loc.latitude, longitude = loc.longitude)
                                queryInput = "Vị trí hiện tại"
                                viewModel.reverseGeocode(loc) { _, address ->
                                    if (address.isNotBlank()) queryInput = address
                                }
                            }
                        },
                        enabled = currentLoc != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, YamahaCyan.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Dùng vị trí hiện tại của tôi", color = YamahaCyan, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = picked != null,
                    onClick = {
                        picked?.let { p ->
                            val place = SavedPlace(
                                name = nameInput.ifBlank { defaultName },
                                address = queryInput,
                                latitude = p.latitude,
                                longitude = p.longitude
                            )
                            if (type == "home") viewModel.setHomePlace(place) else viewModel.setWorkPlace(place)
                        }
                        closeDialog()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan)
                ) {
                    Text("Lưu", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { closeDialog() }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog 3 trạm xăng gần nhất
    if (showGasDialog) {
        AlertDialog(
            onDismissRequest = { showGasDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = NeonOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trạm xăng gần bạn", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        isFetchingGas -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = NeonOrange, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Đang tìm trạm xăng gần nhất...", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                        nearbyGas.isEmpty() -> {
                            Text(gasError ?: "Chưa có dữ liệu trạm xăng.", color = NeonOrange, fontSize = 13.sp)
                        }
                        else -> {
                            nearbyGas.forEachIndexed { index, station ->
                                val distText = currentLoc?.let {
                                    val d = GeoUtils.computeDistanceMeters(it, station.coordinate)
                                    if (d < 1000) "${d.toInt()} m" else String.format(java.util.Locale.US, "%.1f km", d / 1000.0)
                                } ?: ""
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(DarkSurfaceCard, RoundedCornerShape(10.dp))
                                        .clickable {
                                            viewModel.startNavigationTo(station.coordinate, station.name)
                                            showGasDialog = false
                                            onNavigateToMapTab()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(NeonOrange.copy(alpha = 0.2f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("${index + 1}", color = NeonOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(station.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (station.address.isNotBlank()) {
                                            Text(station.address, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (distText.isNotEmpty()) {
                                            Text(distText, color = NeonOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Icon(Icons.Default.Navigation, contentDescription = "Dẫn đường", tint = YamahaCyan, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.fetchNearbyGasStations() }, enabled = !isFetchingGas) {
                    Text("Tìm lại", color = YamahaCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGasDialog = false }) {
                    Text("Đóng", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog thêm Địa điểm yêu thích
    if (showAddFavDialog) {
        var favName by remember { mutableStateOf("") }
        var favAddr by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddFavDialog = false },
            title = { Text("Thêm địa điểm yêu thích", color = TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = favName,
                        onValueChange = { favName = it },
                        label = { Text("Tên (ví dụ: Quán Cà phê, Bạn bè)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = favAddr,
                        onValueChange = { favAddr = it },
                        label = { Text("Địa chỉ hoặc toạ độ") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (favName.isNotBlank()) {
                            viewModel.addFavoritePlace(
                                SavedPlace(
                                    name = favName,
                                    address = favAddr,
                                    latitude = currentLoc?.latitude ?: 10.7769,
                                    longitude = currentLoc?.longitude ?: 106.7009
                                )
                            )
                        }
                        showAddFavDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan)
                ) {
                    Text("Thêm", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFavDialog = false }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun ConnectionStatusCard(
    state: BikeConnectionState,
    connectedBike: BikeDevice?,
    isStreaming: Boolean,
    lastConnectionError: String? = null,
    onClearError: (() -> Unit)? = null,
    onOpenGuide: (() -> Unit)? = null,
    onConnectClick: () -> Unit
) {
    val isConnected = state == BikeConnectionState.CONNECTED
    val statusColor = when {
        isStreaming && isConnected -> NeonGreen
        isStreaming && !isConnected -> NeonOrange
        state == BikeConnectionState.CONNECTED -> YamahaCyan
        state == BikeConnectionState.AUTHENTICATING || state == BikeConnectionState.SCANNING -> NeonOrange
        else -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isStreaming && isConnected -> "Đang chiếu màn hình TFT"
                            isStreaming && !isConnected -> "Đang đợi kết nối với xe..."
                            else -> state.rawValue
                        },
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onConnectClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = statusColor.copy(alpha = 0.2f),
                        contentColor = statusColor
                    ),
                    border = BorderStroke(1.dp, statusColor),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when {
                            isStreaming -> "Dừng chiếu"
                            state == BikeConnectionState.CONNECTED -> "Chiếu lên xe"
                            state == BikeConnectionState.AUTHENTICATING -> "Bắt đầu chiếu"
                            state == BikeConnectionState.SCANNING -> "Đang dò..."
                            state == BikeConnectionState.CONNECTING -> "Đang kết nối..."
                            else -> "Kết nối xe"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = when {
                    isStreaming && !isConnected -> "Đã bật chiếu TFT. Vui lòng kết nối với xe để truyền hình ảnh."
                    connectedBike != null -> "Xe: ${connectedBike.name}"
                    state == BikeConnectionState.CONNECTING -> "Đang thiết lập kết nối Bluetooth với xe..."
                    else -> "Bật chìa khóa xe để tự động kết nối..."
                },
                color = if (connectedBike != null) NeonGreen else if (isStreaming && !isConnected) NeonOrange else TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )

            // Hiển thị thông báo lỗi / hướng dẫn kết nối nếu có
            if (lastConnectionError != null && state != BikeConnectionState.CONNECTED && state != BikeConnectionState.STREAMING) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeonRed.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Thông báo kết nối", color = NeonRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (onClearError != null) {
                                IconButton(onClick = onClearError, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = lastConnectionError,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        if (onOpenGuide != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "💡 Nhấn vào đây để xem cách sửa lỗi Pairing Failed",
                                color = YamahaCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onOpenGuide() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryGridCard(
    telemetry: vn.tftlink.data.model.bike.BikeTelemetry,
    currentRoad: String,
    isConnected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("TỐC ĐỘ", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                val speedDisplay = if (isConnected) "${telemetry.speedKmh.toInt()}" else "--"
                Text(speedDisplay, color = if (isConnected) TextPrimary else TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("km/h", color = TextMuted, fontSize = 10.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = if (isConnected) NeonGreen else TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ẮC QUY", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                val batteryDisplay = if (isConnected && telemetry.batteryVoltage > 0.0) {
                    String.format("%.1fV", telemetry.batteryVoltage)
                } else {
                    "-- V"
                }
                Text(batteryDisplay, color = if (isConnected && telemetry.batteryVoltage > 0.0) TextPrimary else TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Bình điện", color = TextMuted, fontSize = 10.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = NeonOrange, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("TFT ROAD", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (currentRoad.isNotEmpty()) currentRoad else if (isConnected) "Yamaha" else "--",
                    color = if (isConnected) YamahaCyan else TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text("StreetCross", color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun TFTMirrorPreviewCard(
    bitmap: android.graphics.Bitmap?,
    isStreaming: Boolean,
    isConnected: Boolean,
    currentRoad: String,
    zoomDescription: String,
    onToggleStreaming: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Bản đồ TFT Mirror",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (currentRoad.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "▲ $currentRoad",
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .background(NeonGreen.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // TFT Box (480x234 aspect ratio ~ 2.05)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(480f / 234f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(
                        1.5.dp,
                        when {
                            isStreaming && isConnected -> NeonGreen
                            isStreaming && !isConnected -> NeonOrange
                            else -> YamahaCyan.copy(alpha = 0.4f)
                        },
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onToggleStreaming() },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "TFT Stream Preview",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = YamahaCyan, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Đang tải bản đồ TFT...", color = TextSecondary, fontSize = 11.sp)
                    }
                }

                // Streaming status badge placed at TopStart
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = when {
                            isStreaming && isConnected -> "● LIVE TFT"
                            isStreaming && !isConnected -> "○ CHỜ KẾT NỐI XE..."
                            else -> "XEM TRƯỚC (CHẠM ĐỂ CHIẾU)"
                        },
                        color = when {
                            isStreaming && isConnected -> NeonGreen
                            isStreaming && !isConnected -> NeonOrange
                            else -> YamahaCyan
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Zoom description badge placed cleanly at BottomStart
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = zoomDescription,
                        color = YamahaCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Zoom controls (+ / -) placed at BottomEnd (never overlaps with zoom label)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.75f))
                            .border(1.dp, YamahaCyan.copy(alpha = 0.6f), CircleShape)
                            .clickable { onZoomIn() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.75f))
                            .border(1.dp, YamahaCyan.copy(alpha = 0.6f), CircleShape)
                            .clickable { onZoomOut() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("-", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ParkingLocationCard(
    parked: vn.tftlink.data.model.location.ParkedBikeLocation?,
    currentLocation: LatLng?,
    onNavigateToBike: () -> Unit,
    onOpenGoogleMaps: () -> Unit,
    onClearParking: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonPurple),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("P", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VỊ TRÍ ĐỖ XE", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Tự động lưu khi ngắt kết nối", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }

                parked?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            it.relativeTimeString,
                            color = NeonPurple,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier
                                .background(NeonPurple.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onClearParking,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Xóa vị trí đỗ", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (parked != null) {
                val distanceText = currentLocation?.let {
                    val d = GeoUtils.computeDistanceMeters(it, parked.coordinate)
                    if (d < 1000) "${d.toInt()} m" else String.format("%.1f km", d / 1000.0)
                } ?: "Đang tính..."

                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = NeonPurple,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (parked.address.isNotEmpty()) parked.address else "${parked.latitude}, ${parked.longitude}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.DirectionsWalk,
                                contentDescription = null,
                                tint = YamahaCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Cách bạn $distanceText • ${parked.bikeName}",
                                color = YamahaCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onNavigateToBike,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Dẫn đường",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenGoogleMaps,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Google Maps",
                            color = NeonGreen,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text("Chưa có vị trí đỗ xe", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("GPS sẽ tự động ghi nhớ vị trí khi ngắt kết nối xe máy.", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun QuickActionsSection(
    homePlace: SavedPlace?,
    workPlace: SavedPlace?,
    onNavigateToHome: () -> Unit,
    onNavigateToWork: () -> Unit,
    onFindGas: () -> Unit,
    onEditHome: () -> Unit,
    onEditWork: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionButton(
            modifier = Modifier.weight(1f),
            title = "Về nhà",
            subtitle = homePlace?.name ?: "Chưa cài đặt",
            icon = Icons.Default.Home,
            color = NeonGreen,
            onClick = onNavigateToHome,
            onEdit = onEditHome
        )

        QuickActionButton(
            modifier = Modifier.weight(1f),
            title = "Đi làm",
            subtitle = workPlace?.name ?: "Chưa cài đặt",
            icon = Icons.Default.Work,
            color = YamahaCyan,
            onClick = onNavigateToWork,
            onEdit = onEditWork
        )

        QuickActionButton(
            modifier = Modifier.weight(1f),
            title = "Trạm xăng",
            subtitle = "Gần bạn",
            icon = Icons.Default.LocalGasStation,
            color = NeonOrange,
            onClick = onFindGas
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickActionButton(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(subtitle, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onEdit != null) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Sửa địa chỉ",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FavoritePlacesSection(
    favorites: List<SavedPlace>,
    onAddFavorite: () -> Unit,
    onNavigateToFav: (SavedPlace) -> Unit,
    onDeleteFav: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD600), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Địa điểm yêu thích", color = Color(0xFFFFD600), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = onAddFavorite,
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan.copy(alpha = 0.2f), contentColor = YamahaCyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thêm", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (favorites.isEmpty()) {
                Text(
                    "Chưa có địa điểm yêu thích. Nhấn '+ Thêm' để lưu quán ăn, quán cà phê, nhà bạn bè...",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            } else {
                favorites.forEach { fav ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(DarkSurface, RoundedCornerShape(8.dp))
                            .clickable { onNavigateToFav(fav) }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD600), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(fav.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (fav.address.isNotEmpty()) {
                                    Text(fav.address, color = TextMuted, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Navigation, contentDescription = "Dẫn đường", tint = YamahaCyan, modifier = Modifier.size(16.dp))
                            IconButton(onClick = { onDeleteFav(fav.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = NeonRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
