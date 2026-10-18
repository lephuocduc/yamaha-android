package vn.tftlink.service.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Cap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.tftlink.data.model.nav.NavigationDirection
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.nav.OSMRoute
import java.io.ByteArrayOutputStream

/**
 * Engine render bản đồ Google Maps kèm mật độ giao thông thời gian thực (Traffic Layer)
 * chuyên dụng để chiếu trực tiếp lên màn hình cụm đồng hồ xe máy Yamaha (480x234 pixels).
 */
class GoogleMapClusterRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val width: Int = 480,
    private val height: Int = 234
) {
    companion object {
        private const val TAG = "GoogleMapClusterRenderer"

        // Style bản đồ ban đêm tối ưu độ tương phản trên màn hình đồng hồ xe máy
        private const val DARK_MAP_STYLE_JSON = """
        [
          {"elementType":"geometry","stylers":[{"color":"#121824"}]},
          {"elementType":"labels.text.fill","stylers":[{"color":"#c5d0e6"}]},
          {"elementType":"labels.text.stroke","stylers":[{"color":"#0c1018"}]},
          {"featureType":"poi","stylers":[{"visibility":"off"}]},
          {"featureType":"poi.park","stylers":[{"visibility":"on"}]},
          {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#172a24"}]},
          {"featureType":"transit","stylers":[{"visibility":"off"}]},
          {"featureType":"road","elementType":"geometry","stylers":[{"color":"#243248"}]},
          {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#3c4e70"}]},
          {"featureType":"water","elementType":"geometry","stylers":[{"color":"#080c14"}]}
        ]
        """
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var isMapReady = false

    private var activePolyline: Polyline? = null
    private var lastRoutePoints: List<LatLng>? = null

    init {
        mainHandler.post {
            initMapView()
        }
    }

    private fun initMapView() {
        try {
            val options = GoogleMapOptions()
                .liteMode(false)
                .mapType(GoogleMap.MAP_TYPE_NORMAL)
                .compassEnabled(false)
                .zoomControlsEnabled(false)
                .rotateGesturesEnabled(false)
                .scrollGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .zoomGesturesEnabled(false)

            mapView = MapView(context, options).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, width, height)
                onCreate(null)
                onResume()
            }

            mapView?.getMapAsync { map ->
                googleMap = map
                map.isTrafficEnabled = true // Bật mật độ giao thông thời gian thực
                try {
                    map.setMapStyle(MapStyleOptions(DARK_MAP_STYLE_JSON))
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể áp dụng map style: ${e.message}")
                }
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isMyLocationButtonEnabled = false
                map.uiSettings.setAllGesturesEnabled(false)
                isMapReady = true
                Log.d(TAG, "GoogleMapClusterRenderer đã sẵn sàng với Traffic Layer!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo MapView cho cụm đồng hồ: ${e.message}", e)
        }
    }

    fun isReady(): Boolean = isMapReady && googleMap != null

    /**
     * Chụp 1 khung hình Google Maps tại vị trí hiện tại kèm mật độ giao thông và vẽ HUD dẫn đường
     */
    fun captureFrame(
        location: LatLng?,
        course: Float,
        activeRoute: OSMRoute?,
        instruction: NavigationInstruction,
        speedKmh: Double,
        isRerouting: Boolean,
        onFrameReady: (ByteArray) -> Unit
    ) {
        if (!isMapReady || location == null) {
            // Nếu chưa có GPS hoặc Map chưa xong, vẽ màn hình chờ
            val fallbackBitmap = renderFallbackBitmap("Google Maps Traffic", "Đang khởi tạo bản đồ giao thông...", speedKmh)
            onFrameReady(compressToJpeg(fallbackBitmap))
            return
        }

        mainHandler.post {
            val map = googleMap ?: return@post

            // Cập nhật góc nhìn camera: Tọa độ xe, Zoom 16.5f, Bearing theo góc xe, Tilt 45 độ 3D
            val heading = if (course >= 0) course else 0f
            val cameraPosition = CameraPosition.Builder()
                .target(location)
                .zoom(16.5f)
                .bearing(heading)
                .tilt(45f)
                .build()

            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))

            // Cập nhật đường lộ trình trên bản đồ
            val routeCoords = activeRoute?.coordinates
            if (routeCoords != null && routeCoords.isNotEmpty() && routeCoords != lastRoutePoints) {
                lastRoutePoints = routeCoords
                activePolyline?.remove()
                activePolyline = map.addPolyline(
                    PolylineOptions()
                        .addAll(routeCoords)
                        .color(Color.rgb(0, 229, 255)) // Yamaha Cyan
                        .width(14f)
                        .startCap(RoundCap())
                        .endCap(RoundCap())
                        .jointType(JointType.ROUND)
                )
            } else if (routeCoords == null || routeCoords.isEmpty()) {
                activePolyline?.remove()
                activePolyline = null
                lastRoutePoints = null
            }

            // Chụp ảnh snapshot từ Google Maps (đã bao gồm layer Traffic)
            map.snapshot { snapshotBitmap ->
                if (snapshotBitmap != null) {
                    scope.launch(Dispatchers.Default) {
                        val compositeBitmap = overlayClusterHud(snapshotBitmap, instruction, speedKmh, isRerouting)
                        val jpegBytes = compressToJpeg(compositeBitmap)
                        onFrameReady(jpegBytes)
                    }
                }
            }
        }
    }

    /**
     * Vẽ đè HUD chỉ dẫn lên snapshot bản đồ Google Maps
     */
    private fun overlayClusterHud(
        baseBitmap: Bitmap,
        instruction: NavigationInstruction,
        speedKmh: Double,
        isRerouting: Boolean
    ): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. Vẽ snapshot bản đồ Google Maps
        canvas.drawBitmap(baseBitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)

        // 2. Vẽ biểu tượng xe máy ở giữa (phía dưới 60% màn hình cho góc nhìn lái xe)
        val bikeX = width / 2f
        val bikeY = height * 0.65f
        drawBikeMarker(canvas, bikeX, bikeY)

        // 3. Top Banner: HUD Turn-by-Turn
        val hudPaint = Paint().apply {
            color = Color.argb(220, 14, 18, 26)
            style = Paint.Style.FILL
        }
        val hudBorder = Paint().apply {
            color = Color.rgb(0, 229, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val bannerRect = RectF(8f, 6f, (width - 8).toFloat(), 64f)
        canvas.drawRoundRect(bannerRect, 10f, 10f, hudPaint)
        canvas.drawRoundRect(bannerRect, 10f, 10f, hudBorder)

        // Mũi tên chỉ dẫn rẽ
        val arrowColor = if (isRerouting) Color.rgb(255, 171, 0) else Color.rgb(0, 229, 255)
        drawManeuverArrow(canvas, instruction.direction, 36f, 35f, arrowColor)

        // Khoảng cách tới chỗ rẽ
        val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 230, 118) // Neon Green
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(instruction.formattedDistance, 68f, 35f, distPaint)

        // Tên đường kế tiếp
        val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val roadName = if (isRerouting) "Đang tính lại đường..." else (instruction.nextRoadName.takeIf { it.isNotBlank() } ?: instruction.detailedInstruction)
        val truncatedRoad = if (roadName.length > 24) roadName.take(23) + "…" else roadName
        canvas.drawText(truncatedRoad, 68f, 54f, roadPaint)

        // 4. Badge "TRAFFIC LIVE" góc dưới bên trái
        val trafficBadgeRect = RectF(10f, (height - 30).toFloat(), 115f, (height - 10).toFloat())
        val trafficBg = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(trafficBadgeRect, 6f, 6f, trafficBg)

        val greenDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 230, 118)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(22f, (height - 20).toFloat(), 4f, greenDot)

        val trafficText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("TRAFFIC LIVE", 32f, (height - 16).toFloat(), trafficText)

        // 5. Tốc độ xe góc dưới bên phải
        val speedBadgeRect = RectF((width - 90).toFloat(), (height - 32).toFloat(), (width - 8).toFloat(), (height - 8).toFloat())
        canvas.drawRoundRect(speedBadgeRect, 8f, 8f, hudPaint)
        val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("${speedKmh.toInt()} km/h", (width - 82).toFloat(), (height - 15).toFloat(), speedPaint)

        return result
    }

    private fun drawBikeMarker(canvas: Canvas, x: Float, y: Float) {
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0, 229, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 16f, halo)

        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 9f, marker)

        val innerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 4f, innerDot)
    }

    private fun drawManeuverArrow(canvas: Canvas, direction: NavigationDirection, cx: Float, cy: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()

        when (direction) {
            NavigationDirection.TURN_LEFT, NavigationDirection.TURN_SHARP_LEFT -> {
                path.moveTo(cx + 10f, cy + 12f)
                path.lineTo(cx + 10f, cy)
                path.lineTo(cx - 10f, cy)
                path.moveTo(cx - 4f, cy - 6f)
                path.lineTo(cx - 10f, cy)
                path.lineTo(cx - 4f, cy + 6f)
            }
            NavigationDirection.TURN_RIGHT, NavigationDirection.TURN_SHARP_RIGHT -> {
                path.moveTo(cx - 10f, cy + 12f)
                path.lineTo(cx - 10f, cy)
                path.lineTo(cx + 10f, cy)
                path.moveTo(cx + 4f, cy - 6f)
                path.lineTo(cx + 10f, cy)
                path.lineTo(cx + 4f, cy + 6f)
            }
            NavigationDirection.TURN_SLIGHT_LEFT -> {
                path.moveTo(cx + 6f, cy + 12f)
                path.lineTo(cx - 6f, cy - 8f)
                path.moveTo(cx - 10f, cy - 2f)
                path.lineTo(cx - 6f, cy - 8f)
                path.lineTo(cx, cy - 10f)
            }
            NavigationDirection.TURN_SLIGHT_RIGHT -> {
                path.moveTo(cx - 6f, cy + 12f)
                path.lineTo(cx + 6f, cy - 8f)
                path.moveTo(cx + 10f, cy - 2f)
                path.lineTo(cx + 6f, cy - 8f)
                path.lineTo(cx, cy - 10f)
            }
            NavigationDirection.U_TURN -> {
                path.moveTo(cx + 8f, cy + 12f)
                path.lineTo(cx + 8f, cy - 4f)
                path.arcTo(RectF(cx - 8f, cy - 12f, cx + 8f, cy + 4f), 0f, -180f)
                path.lineTo(cx - 8f, cy + 12f)
                path.moveTo(cx - 14f, cy + 6f)
                path.lineTo(cx - 8f, cy + 12f)
                path.lineTo(cx - 2f, cy + 6f)
            }
            else -> {
                // Đi thẳng
                path.moveTo(cx, cy + 12f)
                path.lineTo(cx, cy - 10f)
                path.moveTo(cx - 6f, cy - 4f)
                path.lineTo(cx, cy - 10f)
                path.lineTo(cx + 6f, cy - 4f)
            }
        }

        canvas.drawPath(path, paint)
    }

    private fun renderFallbackBitmap(title: String, subtitle: String, speedKmh: Double): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(18, 24, 36))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 190, 205)
            textSize = 13f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(title, width / 2f, height / 2f - 10f, titlePaint)
        canvas.drawText(subtitle, width / 2f, height / 2f + 18f, subPaint)

        return bitmap
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray()
    }

    fun onResume() {
        mainHandler.post { mapView?.onResume() }
    }

    fun onPause() {
        mainHandler.post { mapView?.onPause() }
    }

    fun onDestroy() {
        mainHandler.post {
            mapView?.onDestroy()
            mapView = null
            googleMap = null
            isMapReady = false
        }
    }
}
