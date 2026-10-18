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

@Composable
fun TripHistoryScreen(viewModel: MainViewModel) {
    val trips by viewModel.tripHistory.collectAsState()
    val isRecording by viewModel.isRecordingSession.collectAsState()
    val sessionDist by viewModel.sessionDistanceMeters.collectAsState()
    val sessionDuration by viewModel.sessionDurationSeconds.collectAsState()

    var showClearAllDialog by remember { mutableStateOf(false) }
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

            if (trips.isNotEmpty()) {
                IconButton(onClick = { showClearAllDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Xóa tất cả",
                        tint = NeonRed.copy(alpha = 0.8f)
                    )
                }
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
                        onDelete = { tripToDelete = trip }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Confirm Clear All Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Xóa toàn bộ lịch sử?", color = TextPrimary) },
            text = { Text("Tất cả lộ trình đã lưu sẽ bị xóa vĩnh viễn khỏi thiết bị.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllTrips()
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Xóa tất cả", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Confirm Delete Single Trip Dialog
    tripToDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            title = { Text("Xóa lộ trình này?", color = TextPrimary) },
            text = { Text("Lộ trình ${trip.formattedDistance} ngày ${trip.formattedDate} sẽ bị xóa.", color = TextSecondary) },
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
    onDelete: () -> Unit
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
                    Text(trip.formattedDate, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(trip.formattedTimeRange, color = TextMuted, fontSize = 12.sp)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = TextMuted, modifier = Modifier.size(16.dp))
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
