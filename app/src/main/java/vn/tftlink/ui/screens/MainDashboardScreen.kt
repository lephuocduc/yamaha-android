package vn.tftlink.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
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
                    currentLoc?.let {
                        // Tìm trạm xăng gần nhất (khoảng cách mẫu 800m)
                        val gasCoord = LatLng(it.latitude + 0.005, it.longitude + 0.005)
                        viewModel.startNavigationTo(gasCoord, "Trạm Xăng Petrolimex")
                        onNavigateToMapTab()
                    }
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
                Text("Tìm xe Yamaha CCU", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Bật chìa khóa xe của bạn để CCU phát sóng Bluetooth.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (discoveredBikes.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = YamahaCyan, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Đang dò tìm thiết bị...", color = YamahaCyan, fontSize = 13.sp)
                        }
                    } else {
                        discoveredBikes.forEach { bike ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.connectBike(bike)
                                        showScanDialog = false
                                    },
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                                border = BorderStroke(1.dp, YamahaCyan)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, tint = YamahaCyan)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(bike.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("Tín hiệu: ${bike.rssi} dBm", color = TextMuted, fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text("Kết nối", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showScanDialog = false
                    viewModel.stopScanning()
                }) {
                    Text("Đóng", color = YamahaCyan)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog lưu Nhà / Cơ quan
    showEditPlaceDialog?.let { type ->
        var nameInput by remember { mutableStateOf(if (type == "home") (homePlace?.name ?: "Nhà riêng") else (workPlace?.name ?: "Cơ quan")) }
        var addressInput by remember { mutableStateOf(if (type == "home") (homePlace?.address ?: "") else (workPlace?.address ?: "")) }

        AlertDialog(
            onDismissRequest = { showEditPlaceDialog = null },
            title = {
                Text(if (type == "home") "Cài đặt địa chỉ Về Nhà" else "Cài đặt địa chỉ Đi Làm", color = TextPrimary)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Tên địa điểm") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        label = { Text("Địa chỉ") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val place = SavedPlace(
                            name = nameInput,
                            address = addressInput,
                            latitude = currentLoc?.latitude ?: 10.7769,
                            longitude = currentLoc?.longitude ?: 106.7009
                        )
                        if (type == "home") viewModel.setHomePlace(place) else viewModel.setWorkPlace(place)
                        showEditPlaceDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan)
                ) {
                    Text("Lưu", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPlaceDialog = null }) {
                    Text("Hủy", color = TextSecondary)
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
                    else -> "Bật chìa khóa xe để tự động kết nối..."
                },
                color = if (connectedBike != null) NeonGreen else if (isStreaming && !isConnected) NeonOrange else TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )
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
                        CircularProgressIndicator(color = YamahaCyan, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Bản đồ TFT 480 × 234", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = when {
                                isStreaming && isConnected -> "Đang chiếu live lên đồng hồ xe"
                                isStreaming && !isConnected -> "Đang đợi kết nối với xe..."
                                else -> "Chạm để phát luồng live lên xe"
                            },
                            color = if (isStreaming && !isConnected) NeonOrange else TextSecondary,
                            fontSize = 11.sp
                        )
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonPurple),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("P", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("VỊ TRÍ ĐỖ XE (FIND MY BIKE)", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Tự động lưu GPS khi tắt khóa xe", color = TextMuted, fontSize = 10.sp)
                    }
                }

                parked?.let {
                    Text(
                        it.relativeTimeString,
                        color = NeonPurple,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(NeonPurple.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (parked.address.isNotEmpty()) parked.address else "${parked.latitude}, ${parked.longitude}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cách bạn $distanceText • ${parked.bikeName}", color = YamahaCyan, fontSize = 11.sp)
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
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Dẫn đường tới xe", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onOpenGoogleMaps,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NeonGreen)
                    ) {
                        Text("Google Maps", color = NeonGreen, fontSize = 12.sp)
                    }

                    IconButton(onClick = onClearParking) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = TextMuted)
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
            subtitle = homePlace?.name ?: "Cài đặt",
            icon = Icons.Default.Home,
            color = NeonGreen,
            onClick = onNavigateToHome
        )

        QuickActionButton(
            modifier = Modifier.weight(1f),
            title = "Đi làm",
            subtitle = workPlace?.name ?: "Cài đặt",
            icon = Icons.Default.Work,
            color = YamahaCyan,
            onClick = onNavigateToWork
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

@Composable
fun QuickActionButton(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = TextSecondary, fontSize = 10.sp, maxLines = 1)
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
