package vn.tftlink.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
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

@SuppressLint("MissingPermission")
@Composable
fun LiveNavigationScreen(viewModel: MainViewModel) {
    val currentLoc by viewModel.currentLocation.collectAsState()
    val heading by viewModel.currentHeading.collectAsState()
    val isNavigating by viewModel.isNavigating.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val instruction by viewModel.currentInstruction.collectAsState()
    val isNorthUp by viewModel.isNorthUp.collectAsState()
    val lastFrameBitmap by viewModel.lastFrameBitmap.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val currentRoad by viewModel.currentRoadNameOnTFT.collectAsState()
    val parkedLoc by viewModel.parkedLocation.collectAsState()

    var showMiniPreview by remember { mutableStateOf(true) }
    var showSearchDialog by remember { mutableStateOf(false) }

    val defaultPosition = LatLng(10.7769, 106.7009) // Ho Chi Minh City
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLoc ?: defaultPosition, 16f)
    }

    // Auto follow user location if available
    LaunchedEffect(currentLoc, heading, isNorthUp) {
        val loc = currentLoc ?: return@LaunchedEffect
        val bearing = if (isNorthUp) 0f else heading
        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(loc)
                    .zoom(if (isNavigating) 17f else 15.5f)
                    .bearing(bearing)
                    .tilt(if (isNorthUp) 0f else 45f)
                    .build()
            ),
            800
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Google Maps
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = true,
                mapType = MapType.NORMAL
            ),
            uiSettings = MapUiSettings(
                compassEnabled = true,
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false
            )
        ) {
            // Draw Active Route Polyline
            activeRoute?.let { route ->
                if (route.coordinates.isNotEmpty()) {
                    Polyline(
                        points = route.coordinates,
                        color = YamahaCyan,
                        width = 16f
                    )
                    Marker(
                        state = MarkerState(position = route.coordinates.last()),
                        title = "Điểm đến",
                        snippet = instruction.roadName
                    )
                }
            }

            // Draw Parked Bike Pin if available
            parkedLoc?.let { parked ->
                Marker(
                    state = MarkerState(position = parked.coordinate),
                    title = "Vị trí đỗ xe: ${parked.bikeName}",
                    snippet = parked.relativeTimeString
                )
            }
        }

        // 2. Top Banner: Turn-by-Turn or Search Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        ) {
            if (isNavigating) {
                // Turn-by-Turn Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard.copy(alpha = 0.95f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(YamahaCyan)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(YamahaCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Directions,
                                contentDescription = null,
                                tint = YamahaCyan,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (instruction.distanceMeters < 1000) "${instruction.distanceMeters} m" else String.format("%.1f km", instruction.distanceMeters / 1000.0),
                                    color = NeonGreen,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = instruction.roadName,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Còn ${instruction.totalTimeRemainingSeconds / 60} phút • Đồng hồ xe đang hiển thị",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        IconButton(
                            onClick = { viewModel.stopNavigation() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dừng dẫn đường",
                                tint = NeonRed
                            )
                        }
                    }
                }
            } else {
                // Quick Search Bar
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSearchDialog = true },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard.copy(alpha = 0.9f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = YamahaCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Tìm kiếm điểm đến trên bản đồ...",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // 3. Floating Action Controls (Right side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Orientation mode (North-Up vs Heading Tracking)
            FloatingActionButton(
                onClick = { viewModel.toggleNorthUp() },
                containerColor = DarkSurfaceCard,
                contentColor = if (isNorthUp) NeonOrange else YamahaCyan,
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = "Chế độ xoay",
                    modifier = Modifier.size(22.dp)
                )
            }

            // Recenter My Location
            FloatingActionButton(
                onClick = {
                    currentLoc?.let {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 16.5f))
                    }
                },
                containerColor = DarkSurfaceCard,
                contentColor = YamahaCyan,
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Vị trí của tôi",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 4. Mini TFT Mirror Card (Bottom Left / Draggable overlay)
        if (showMiniPreview) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .width(160.dp)
                    .aspectRatio(480f / 234f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, if (isStreaming) NeonGreen else YamahaCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable { showMiniPreview = false },
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (lastFrameBitmap != null) {
                        Image(
                            bitmap = lastFrameBitmap!!.asImageBitmap(),
                            contentDescription = "TFT Mini Stream",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            "TFT Mirror",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    Text(
                        text = if (isStreaming) "LIVE TFT" else "PAUSED",
                        color = if (isStreaming) NeonGreen else TextMuted,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    // Search dialog
    if (showSearchDialog) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Tìm kiếm điểm đến", color = TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Nhập địa chỉ / tên đường") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Gợi ý: Tìm kiếm địa điểm và chạm 'Bắt đầu' để vẽ lộ trình sang đồng hồ xe máy.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val base = currentLoc ?: LatLng(10.7769, 106.7009)
                        val target = LatLng(base.latitude + 0.015, base.longitude + 0.012)
                        viewModel.startNavigationTo(target, if (query.isNotBlank()) query else "Điểm đến đã chọn")
                        showSearchDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan)
                ) {
                    Text("Bắt đầu", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
