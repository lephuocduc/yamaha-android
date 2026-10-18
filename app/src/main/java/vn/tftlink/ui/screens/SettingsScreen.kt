package vn.tftlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.tftlink.service.protocol.StreetCrossProtocol
import vn.tftlink.service.renderer.OSMTileEngine
import vn.tftlink.ui.MainViewModel
import vn.tftlink.ui.theme.DarkBackground
import vn.tftlink.ui.theme.DarkSurfaceBorder
import vn.tftlink.ui.theme.DarkSurfaceCard
import vn.tftlink.ui.theme.NeonGreen
import vn.tftlink.ui.theme.TextMuted
import vn.tftlink.ui.theme.TextPrimary
import vn.tftlink.ui.theme.TextSecondary
import vn.tftlink.ui.theme.YamahaCyan

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedBike by viewModel.connectedBike.collectAsState()

    var selectedBikeModel by remember { mutableStateOf("Yamaha NMAX 155") }
    var autoReconnect by remember { mutableStateOf(true) }
    var selectedStyle by remember { mutableStateOf("CartoDB Voyager (Khuyên dùng - Bản quyền API)") }
    var cartoApiKey by remember { mutableStateOf(OSMTileEngine.defaultCartoApiKey) }

    val bikeModels = listOf(
        "Yamaha NMAX 155",
        "Yamaha XMAX 300",
        "Yamaha NVX / Aerox 155",
        "Yamaha YZF-R9",
        "Yamaha MT-09 / SP",
        "Yamaha XSR900 GP",
        "Xe hỗ trợ Garmin StreetCross khác"
    )

    val tileStyles = listOf(
        "CartoDB Voyager (Khuyên dùng - Bản quyền API)",
        "CartoDB Positron (Bản đồ sáng tối giản)",
        "CartoDB Dark Matter (Bản đồ đêm chuyên dụng)",
        "OpenStreetMap Chuẩn (FOSSGIS)",
        "OpenStreetMap Quốc Tế (Wikimedia)",
        "OpenStreetMap Xe 2 Bánh (CyclOSM)"
    )

    var bikeMenuExpanded by remember { mutableStateOf(false) }
    var styleMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cài đặt hệ thống",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Cấu hình xe, bản đồ và giao diện chiếu đồng hồ",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Section: Dòng xe Yamaha
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("DÒNG XE YAMAHA", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedBikeModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Mẫu xe đang dùng") },
                            trailingIcon = {
                                IconButton(onClick = { bikeMenuExpanded = !bikeMenuExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { bikeMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = bikeMenuExpanded,
                            onDismissRequest = { bikeMenuExpanded = false }
                        ) {
                            bikeModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        selectedBikeModel = model
                                        bikeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tự động kết nối lại", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Kết nối ngay khi mở chìa khóa xe", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoReconnect,
                            onCheckedChange = { autoReconnect = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = YamahaCyan, checkedTrackColor = YamahaCyan.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }

        // Section: Bản đồ & Thuật toán dẫn đường
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("BẢN ĐỒ & THUẬT TOÁN DẪN ĐƯỜNG", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedStyle,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Giao diện ngói bản đồ") },
                            trailingIcon = {
                                IconButton(onClick = { styleMenuExpanded = !styleMenuExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { styleMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = styleMenuExpanded,
                            onDismissRequest = { styleMenuExpanded = false }
                        ) {
                            tileStyles.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style) },
                                    onClick = {
                                        selectedStyle = style
                                        styleMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = cartoApiKey,
                        onValueChange = { cartoApiKey = it },
                        label = { Text("CartoDB API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tầng thuật toán", color = TextSecondary, fontSize = 13.sp)
                        Text("Valhalla Engine (Motorcycle)", color = NeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("✓ Bản đồ ngói CartoDB Voyager sạch bóng, không watermark", color = NeonGreen, fontSize = 11.sp)
                    Text("✓ Thuật toán Valhalla Engine định tuyến xe máy chuẩn Việt Nam", color = NeonGreen, fontSize = 11.sp)
                    Text("✓ Tốc độ nạp ngói cực nhanh qua mạng lưới CDN toàn cầu", color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        // Section: Thông tin thiết bị kết nối
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("THÔNG TIN PHẦN CỨNG & KẾT NỐI", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Trạng thái BLE CCU", color = TextSecondary, fontSize = 13.sp)
                        Text(connectionState.rawValue, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    connectedBike?.let { bike ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tên Bluetooth xe", color = TextSecondary, fontSize = 13.sp)
                            Text(bike.name, color = NeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Độ phân giải màn hình TFT", color = TextSecondary, fontSize = 13.sp)
                        Text("480 × 234 pixels", color = TextPrimary, fontSize = 13.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Mã phần mềm Garmin", color = TextSecondary, fontSize = 13.sp)
                        Text(StreetCrossProtocol.softwarePartNumber, color = TextPrimary, fontSize = 13.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kênh truyền đồ họa", color = TextSecondary, fontSize = 13.sp)
                        Text("SPP RFCOMM Socket / BLE Fallback", color = TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        }

        // Section: Thông tin ứng dụng
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("THÔNG TIN PHẦN MỀM", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("NConnect Android (Yamaha CCU Companion)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Phiên bản: 0.5.23 (Build 53)", color = TextSecondary, fontSize = 12.sp)
                    Text("Package: vn.tftlink", color = TextMuted, fontSize = 11.sp)
                    Text("Kiến trúc: 100% Kotlin • Jetpack Compose • Material 3 • Coroutines/StateFlow • Room DB", color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
