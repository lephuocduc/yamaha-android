package vn.tftlink.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.tftlink.data.model.trip.TripRecord
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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.overlay.Polyline as OsmPolyline
import org.osmdroid.views.overlay.Marker as OsmMarker
import androidx.core.content.ContextCompat
import vn.tftlink.R

@Composable
fun TripHistoryScreen(viewModel: MainViewModel) {
    val trips by viewModel.tripHistory.collectAsState()
    val isRecording by viewModel.isRecordingSession.collectAsState()
    val sessionDist by viewModel.sessionDistanceMeters.collectAsState()
    val sessionDuration by viewModel.sessionDurationSeconds.collectAsState()

    var selectedTripForDetail by remember { mutableStateOf<TripRecord?>(null) }
    var tripToDelete by remember { mutableStateOf<TripRecord?>(null) }

    val totalDistanceMeters = trips.sumOf { it.distanceMeters }
    val totalSeconds = trips.sumOf { it.durationSeconds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Lộ trình di chuyển",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Lịch sử chuyến đi và thông số xe",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Live Session Card (if recording)
            if (isRecording) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF092916)),
                        border = BorderStroke(1.dp, NeonGreen)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(NeonGreen)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "ĐANG THEO DÕI LỘ TRÌNH XE CHẠY",
                                        color = NeonGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Quãng đường", color = TextSecondary, fontSize = 11.sp)
                                    val distStr = if (sessionDist < 1000) "${sessionDist.toInt()} m" else String.format("%.2f km", sessionDist / 1000.0)
                                    Text(distStr, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Thời gian", color = TextSecondary, fontSize = 11.sp)
                                    val m = sessionDuration / 60
                                    val s = sessionDuration % 60
                                    Text(String.format("%02d:%02d", m, s), color = YamahaCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Summary Stats Card
            if (trips.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = BorderStroke(1.dp, DarkSurfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TỔNG CHUYẾN", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("${trips.size}", color = YamahaCyan, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("QUÃNG ĐƯỜNG", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                val km = totalDistanceMeters / 1000.0
                                Text(String.format("%.1f km", km), color = NeonGreen, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("THỜI GIAN", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                val hours = totalSeconds / 3600
                                val mins = (totalSeconds % 3600) / 60
                                Text("${hours}h ${mins}p", color = NeonOrange, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }

            // 3. Trips List or Empty State
            if (trips.isEmpty() && !isRecording) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = BorderStroke(1.dp, DarkSurfaceBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Chưa có lộ trình nào được ghi lại",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Khi bạn kết nối xe máy Yamaha và chạy xe, ứng dụng sẽ tự động ghi lại quãng đường, vận tốc và thời gian.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(trips, key = { it.id }) { trip ->
                    TripRecordCard(
                        trip = trip,
                        onClick = { selectedTripForDetail = trip },
                        onDelete = { tripToDelete = trip }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Detail Dialog with Interactive Map & Comprehensive Metrics
    selectedTripForDetail?.let { trip ->
        TripDetailDialog(
            trip = trip,
            onDismiss = { selectedTripForDetail = null }
        )
    }

    // Xác nhận xóa từng chuyến đi cụ thể
    tripToDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            title = { Text("Xóa lộ trình này?", color = TextPrimary) },
            text = { Text("Lộ trình ${trip.formattedDistance} ngày ${trip.formattedDate} sẽ bị xóa khỏi lịch sử.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTrip(trip.id)
                        tripToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Xóa", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun TripRecordCard(
    trip: TripRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                    Text(trip.formattedDate, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(trip.formattedTimeRange, color = TextMuted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Xóa chuyến đi",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Chi tiết", color = YamahaCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Xem chi tiết",
                        tint = YamahaCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Quãng đường", color = TextSecondary, fontSize = 11.sp)
                    Text(trip.formattedDistance, color = YamahaCyan, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                Column {
                    Text("Thời gian", color = TextSecondary, fontSize = 11.sp)
                    Text(trip.formattedDuration, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("TB / Tối đa", color = TextSecondary, fontSize = 11.sp)
                    Text("${trip.averageSpeedKmh.toInt()} / ${trip.maxSpeedKmh.toInt()} km/h", color = NeonOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (trip.startAddress.isNotEmpty() || trip.endAddress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${trip.startAddress} → ${trip.endAddress}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun TripDetailDialog(
    trip: TripRecord,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val polyPoints = remember(trip) { trip.getParsedPolylinePoints() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(scrollState)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Chi tiết lộ trình",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${trip.formattedDate} · ${trip.formattedTimeRange}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Đóng", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Map View Preview
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DarkSurfaceBorder)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setMultiTouchControls(true)
                                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                                isTilesScaledToDpi = true

                                val pointsToDraw = if (polyPoints.isNotEmpty()) {
                                    polyPoints.map { GeoPoint(it.latitude, it.longitude) }
                                } else if (trip.startLatitude != 0.0 && trip.endLatitude != 0.0) {
                                    listOf(
                                        GeoPoint(trip.startLatitude, trip.startLongitude),
                                        GeoPoint(trip.endLatitude, trip.endLongitude)
                                    )
                                } else {
                                    emptyList()
                                }

                                if (pointsToDraw.isNotEmpty()) {
                                    val polyline = OsmPolyline().apply {
                                        outlinePaint.color = android.graphics.Color.parseColor("#00E5FF")
                                        outlinePaint.strokeWidth = 14f
                                        setPoints(pointsToDraw)
                                    }
                                    overlays.add(polyline)

                                    // Start marker
                                    val startPoint = pointsToDraw.first()
                                    val startMarker = OsmMarker(this).apply {
                                        position = startPoint
                                        title = "Xuất phát"
                                        snippet = trip.startAddress
                                        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_marker_user_puck)
                                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                                    }
                                    overlays.add(startMarker)

                                    // End marker
                                    val endPoint = pointsToDraw.last()
                                    val endMarker = OsmMarker(this).apply {
                                        position = endPoint
                                        title = "Điểm kết thúc"
                                        snippet = trip.endAddress
                                        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_marker_google_pin)
                                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                                    }
                                    overlays.add(endMarker)

                                    // Zoom to BoundingBox
                                    val lats = pointsToDraw.map { it.latitude }
                                    val lons = pointsToDraw.map { it.longitude }
                                    val minLat = lats.minOrNull() ?: 0.0
                                    val maxLat = lats.maxOrNull() ?: 0.0
                                    val minLon = lons.minOrNull() ?: 0.0
                                    val maxLon = lons.maxOrNull() ?: 0.0

                                    val latMargin = ((maxLat - minLat) * 0.2).coerceAtLeast(0.003)
                                    val lonMargin = ((maxLon - minLon) * 0.2).coerceAtLeast(0.003)
                                    val box = BoundingBox(
                                        maxLat + latMargin,
                                        maxLon + lonMargin,
                                        minLat - latMargin,
                                        minLon - lonMargin
                                    )
                                    post {
                                        try {
                                            zoomToBoundingBox(box, false, 60)
                                        } catch (_: Exception) {
                                            controller.setCenter(startPoint)
                                            controller.setZoom(15.0)
                                        }
                                    }
                                } else {
                                    controller.setCenter(GeoPoint(trip.startLatitude, trip.startLongitude))
                                    controller.setZoom(15.0)
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Metrics Grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurfaceCard)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TripMetricRow(title = "Quãng đường", value = trip.formattedDistance, icon = Icons.Default.Straighten, tint = NeonGreen)
                    TripMetricRow(title = "Thời gian di chuyển", value = trip.formattedDuration, icon = Icons.Default.Timer, tint = YamahaCyan)
                    TripMetricRow(title = "Tốc độ trung bình", value = "${trip.averageSpeedKmh} km/h", icon = Icons.Default.Speed, tint = NeonOrange)
                    TripMetricRow(title = "Tốc độ tối đa", value = "${trip.maxSpeedKmh} km/h", icon = Icons.Default.Speed, tint = NeonRed)
                    TripMetricRow(title = "Trạng thái đỗ xe", value = trip.relativeParkingDuration, icon = Icons.Default.Place, tint = YamahaCyan)
                    TripMetricRow(title = "Xe máy", value = trip.bikeName, icon = Icons.Default.TwoWheeler, tint = NeonPurple)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Addresses
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurfaceCard)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CHI TIẾT ĐỊA ĐIỂM", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NeonGreen)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Điểm xuất phát:", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                text = if (trip.startAddress.isNotBlank()) trip.startAddress else "Không có địa chỉ",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NeonRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Điểm kết thúc:", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                text = if (trip.endAddress.isNotBlank()) trip.endAddress else "Không có địa chỉ",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Đóng", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun TripMetricRow(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, color = TextSecondary, fontSize = 12.sp)
        }
        Text(text = value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
