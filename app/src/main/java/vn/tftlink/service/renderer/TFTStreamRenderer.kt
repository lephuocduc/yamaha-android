package vn.tftlink.service.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.nav.OSMRoute
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Renderer tạo khung hình Bản Đồ OpenStreetMap cho màn hình TFT xe máy Yamaha
 * Tối ưu hóa 100% chạy nền qua Canvas / Bitmap pipeline, nén JPEG gửi lên xe
 */
enum class ClusterMapSource(
    val id: String,
    val title: String,
    val tileUrlTemplate: String,
    val styleKey: String,
    val attribution: String
) {
    GOOGLE_MAPS_TRAFFIC(
        "gmap_traffic",
        "Google Maps (Mật độ giao thông LIVE - Khuyên dùng)",
        "https://mt1.google.com/vt/lyrs=m,traffic&hl=vi&x={x}&y={y}&z={z}",
        "gmaps_traffic",
        "© Google Maps Traffic"
    ),
    OSM_CARTO(
        "osm_carto",
        "CartoDB Voyager (OpenStreetMap)",
        "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=cb1_4mz7_1_fb3f57cfec3a69a35eb8be87",
        "voyager",
        "© OpenStreetMap • CartoDB"
    )
}

class TFTStreamRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    var targetWidth: Int = 480,
    var targetHeight: Int = 234
) {
    companion object {
        val ZOOM_LEVELS = doubleArrayOf(200.0, 400.0, 800.0, 1600.0, 3000.0)
        val OSM_ZOOMS = intArrayOf(18, 17, 16, 15, 14)
    }

    private val tileEngine = OSMTileEngine.getInstance(context)
    private val renderMutex = Mutex()

    var clusterMapSource: ClusterMapSource = ClusterMapSource.GOOGLE_MAPS_TRAFFIC
        set(value) {
            field = value
            tileEngine.updateStyle(value.tileUrlTemplate, value.styleKey)
        }

    init {
        tileEngine.updateStyle(clusterMapSource.tileUrlTemplate, clusterMapSource.styleKey)
    }

    private val _lastFrameBitmap = MutableStateFlow<Bitmap?>(null)
    val lastFrameBitmap: StateFlow<Bitmap?> = _lastFrameBitmap.asStateFlow()

    var currentZoomIndex: Int = 0
    var isNorthUp: Boolean = false

    val currentOsmZoom: Int
        get() = OSM_ZOOMS[currentZoomIndex.coerceIn(0, OSM_ZOOMS.size - 1)]

    val currentZoomLevel: Int
        get() = currentOsmZoom

    fun updateLocation(loc: LatLng, bearing: Float) {
        lastRenderedLocation = loc
        lastRenderedCourse = bearing
    }

    private var lastRenderedLocation: LatLng? = null
    private var lastRenderedCourse: Float = 0f
    private var lastRenderedRoute: OSMRoute? = null
    private var lastRenderedAlternativeRoutes: List<OSMRoute> = emptyList()
    private var lastRenderedInstruction: NavigationInstruction = NavigationInstruction()
    private var lastRenderedSpeedKmh: Double = 0.0
    private var lastRenderedIsRerouting: Boolean = false
    private var lastPrefetchedRouteKey: String = ""

    private var streamingJob: Job? = null

    fun zoomIn(onFrameReady: ((ByteArray) -> Unit)? = null) {
        if (currentZoomIndex > 0) {
            currentZoomIndex--
            triggerImmediateReRender(onFrameReady)
        }
    }

    fun zoomOut(onFrameReady: ((ByteArray) -> Unit)? = null) {
        if (currentZoomIndex < ZOOM_LEVELS.size - 1) {
            currentZoomIndex++
            triggerImmediateReRender(onFrameReady)
        }
    }

    private fun triggerImmediateReRender(onFrameReady: ((ByteArray) -> Unit)?) {
        val loc = lastRenderedLocation ?: return
        scope.launch(Dispatchers.Default) {
            val bytes = renderLiveMapFrame(
                location = loc,
                course = lastRenderedCourse,
                activeRoute = lastRenderedRoute,
                alternativeRoutes = lastRenderedAlternativeRoutes,
                instruction = lastRenderedInstruction,
                speedKmh = lastRenderedSpeedKmh,
                isRerouting = lastRenderedIsRerouting
            )
            bytes?.let { onFrameReady?.invoke(it) }
        }
    }

    /**
     * Bắt đầu luồng stream hình ảnh định kỳ lên màn hình TFT (chu kỳ ~1.2s)
     */
    fun startStreamingLoop(
        getLocation: () -> LatLng?,
        getCourse: () -> Float,
        getActiveRoute: () -> OSMRoute?,
        getAlternativeRoutes: () -> List<OSMRoute>,
        getInstruction: () -> NavigationInstruction,
        getSpeed: () -> Double,
        getIsRerouting: () -> Boolean,
        onFrameReady: (ByteArray) -> Unit
    ) {
        stopStreamingLoop()
        streamingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val loc = getLocation()
                val course = getCourse()
                val route = getActiveRoute()
                val alts = getAlternativeRoutes()
                val inst = getInstruction()
                val speed = getSpeed()
                val rerouting = getIsRerouting()

                val bytes = renderLiveMapFrame(loc, course, route, alts, inst, speed, rerouting)
                if (bytes != null) {
                    onFrameReady(bytes)
                }
                delay(1200)
            }
        }
    }

    fun stopStreamingLoop() {
        streamingJob?.cancel()
        streamingJob = null
    }

    /**
     * Render 1 khung hình bản đồ live hoàn chỉnh và nén JPEG
     */
    suspend fun renderLiveMapFrame(
        location: LatLng?,
        course: Float,
        activeRoute: OSMRoute?,
        alternativeRoutes: List<OSMRoute> = emptyList(),
        instruction: NavigationInstruction,
        speedKmh: Double,
        isRerouting: Boolean = false
    ): ByteArray? = renderMutex.withLock {
        this.lastRenderedLocation = location
        this.lastRenderedCourse = course
        this.lastRenderedRoute = activeRoute
        this.lastRenderedAlternativeRoutes = alternativeRoutes
        this.lastRenderedInstruction = instruction
        this.lastRenderedSpeedKmh = speedKmh
        this.lastRenderedIsRerouting = isRerouting

        // Tự động tải trước ngói bản đồ dọc lộ trình
        if (activeRoute != null && activeRoute.coordinates.isNotEmpty()) {
            val key = "${activeRoute.coordinates.size}_${activeRoute.distanceMeters.toInt()}"
            if (key != lastPrefetchedRouteKey) {
                lastPrefetchedRouteKey = key
                tileEngine.prefetchRouteTiles(scope, activeRoute.coordinates, currentOsmZoom)
            }
        }

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Nền tối chống chói ban đêm (#141A24)
        canvas.drawColor(Color.rgb(20, 26, 36))

        if (location == null) {
            val title = if (clusterMapSource == ClusterMapSource.GOOGLE_MAPS_TRAFFIC) "Google Maps Live Traffic" else "OpenStreetMap"
            drawFallbackScreen(canvas, title, "Đang lấy tín hiệu GPS...", speedKmh)
            _lastFrameBitmap.value = bitmap
            return@withLock compressToJpeg(bitmap)
        }

        val zoom = currentOsmZoom
        val n = 2.0.pow(zoom)
        val lon = location.longitude
        val lat = location.latitude.coerceIn(-85.0511, 85.0511)

        val worldX = (lon + 180.0) / 360.0 * n * 256.0
        val latRad = Math.toRadians(lat)
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n * 256.0

        val centerTileX = (worldX / 256.0).toInt()
        val centerTileY = (worldY / 256.0).toInt()

        val originWorldX = ((centerTileX - 1) * 256).toDouble()
        val originWorldY = ((centerTileY - 1) * 256).toDouble()

        val bikeCanvasX = (worldX - originWorldX).toFloat()
        val bikeCanvasY = (worldY - originWorldY).toFloat()

        val heading = if (course >= 0) course else 0f
        val mapRotation = if (isNorthUp) 0f else heading
        val centerPtX = targetWidth / 2f
        val centerPtY = targetHeight / 2f

        canvas.save()
        // Xoay bản đồ theo hướng di chuyển của xe (Heading Tracking)
        canvas.translate(centerPtX, centerPtY)
        canvas.rotate(-mapRotation)
        canvas.translate(-bikeCanvasX, -bikeCanvasY)

        // 2. Vẽ 9 ngói OpenStreetMap (3x3 grid) xung quanh vị trí xe
        val gridPaint = Paint().apply {
            color = Color.rgb(26, 31, 41)
            style = Paint.Style.FILL
        }
        val gridBorder = Paint().apply {
            color = Color.argb(76, 51, 51, 51)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                val left = (dx + 1) * 256f
                val top = (dy + 1) * 256f
                val rect = RectF(left, top, left + 256f, top + 256f)

                val tile = tileEngine.getCachedTile(tx, ty, zoom)
                if (tile != null) {
                    canvas.drawBitmap(tile, null, rect, null)
                } else {
                    canvas.drawRect(rect, gridPaint)
                    canvas.drawRect(rect, gridBorder)
                    scope.launch(Dispatchers.IO) {
                        tileEngine.fetchTile(tx, ty, zoom)
                    }
                }
            }
        }

        // Tăng độ tương phản cho nền bản đồ ngoài trời nắng
        val contrastPaint = Paint().apply {
            color = Color.argb(40, 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, 768f, 768f, contrastPaint)

        // 3. Vẽ lộ trình OpenStreetMap (Polyline)
        for (alt in alternativeRoutes) {
            if (alt.coordinates.size > 1) {
                drawRoutePolyline(canvas, alt, zoom, originWorldX, originWorldY, isAlternative = true)
            }
        }
        if (activeRoute != null && activeRoute.coordinates.size > 1) {
            drawRoutePolyline(canvas, activeRoute, zoom, originWorldX, originWorldY, isAlternative = false)
        }

        canvas.restore()

        // 4. Vẽ biểu tượng xe máy trung tâm màn hình (Chevron xanh Neon Cyan)
        drawMotorcycleMarker(canvas, centerPtX, centerPtY, heading, isNorthUp)

        // 5. Thanh chỉ dẫn điều hướng Turn-by-Turn phía trên (khoảng cách + tên đường)
        drawTopNavigationBanner(canvas, instruction, isRerouting)

        // 6. Nhãn bản quyền
        drawAttribution(canvas)

        _lastFrameBitmap.value = bitmap
        compressToJpeg(bitmap)
    }

    /**
     * Vẽ lộ trình đa lớp tương phản cao chống chói ngoài trời
     */
    private fun drawRoutePolyline(
        canvas: Canvas,
        route: OSMRoute,
        zoom: Int,
        originX: Double,
        originY: Double,
        isAlternative: Boolean
    ) {
        val n = 2.0.pow(zoom)
        val path = Path()
        var started = false

        for (coord in route.coordinates) {
            val ptWorldX = (coord.longitude + 180.0) / 360.0 * n * 256.0
            val safeLat = coord.latitude.coerceIn(-85.0511, 85.0511)
            val ptLatRad = Math.toRadians(safeLat)
            val ptWorldY = (1.0 - ln(tan(ptLatRad) + 1.0 / cos(ptLatRad)) / Math.PI) / 2.0 * n * 256.0

            val px = (ptWorldX - originX).toFloat()
            val py = (ptWorldY - originY).toFloat()

            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }

        if (!started) return

        if (isAlternative) {
            // Tuyến thay thế: viền đen 12px + thân xám đá 7px
            val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(204, 13, 20, 31)
                style = Paint.Style.STROKE
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(217, 128, 153, 179)
                style = Paint.Style.STROKE
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, outer)
            canvas.drawPath(path, inner)
        } else {
            // Tuyến chính: Viền đen cực dày 18px + Thân Racing Neon Cyan 12px + Lõi trắng 4.5px
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 10, 31)
                style = Paint.Style.STROKE
                strokeWidth = 18f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 224, 255) // Racing Neon Cyan
                style = Paint.Style.STROKE
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4.5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, outline)
            canvas.drawPath(path, body)
            canvas.drawPath(path, core)
        }
    }

    /**
     * Vẽ biểu tượng xe máy trung tâm màn hình
     */
    private fun drawMotorcycleMarker(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        heading: Float,
        isNorthUp: Boolean
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        if (isNorthUp) {
            canvas.rotate(heading)
        }

        // Vòng hào quang phát sáng
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(89, 0, 153, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(0f, 0f, 22f, haloPaint)

        // Viền đen tương phản
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 13, 31)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(0f, 0f, 13f, borderPaint)

        // Vòng thân xe xanh Cyan
        val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 224, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(0f, 0f, 11f, cyanPaint)

        // Mũi tên định hướng trắng
        val arrowPath = Path().apply {
            moveTo(0f, -15f)
            lineTo(8f, 9f)
            lineTo(0f, 4.5f)
            lineTo(-8f, 9f)
            close()
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(arrowPath, whitePaint)

        canvas.restore()
    }

    /**
     * Dải banner chỉ dẫn Turn-by-Turn phía trên (khoảng cách Neon Green + Tên đường trắng)
     */
    private fun drawTopNavigationBanner(
        canvas: Canvas,
        instruction: NavigationInstruction,
        isRerouting: Boolean
    ) {
        if (!instruction.isNavigating && !isRerouting && !instruction.hasArrived) return

        val bannerHeight = 36f
        val bannerRect = RectF(0f, 0f, targetWidth.toFloat(), bannerHeight)

        if (isRerouting) {
            val bgPaint = Paint().apply { color = Color.argb(250, 31, 20, 5) }
            val linePaint = Paint().apply {
                color = Color.YELLOW
                strokeWidth = 2f
            }
            canvas.drawRect(bannerRect, bgPaint)
            canvas.drawLine(0f, bannerHeight, targetWidth.toFloat(), bannerHeight, linePaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("⚠️ ĐANG TÍNH LẠI LỘ TRÌNH MỚI...", 12f, 24f, textPaint)
            return
        }

        if (instruction.hasArrived) {
            val bgPaint = Paint().apply { color = Color.argb(250, 8, 38, 15) }
            val linePaint = Paint().apply {
                color = Color.rgb(0, 255, 115)
                strokeWidth = 2f
            }
            canvas.drawRect(bannerRect, bgPaint)
            canvas.drawLine(0f, bannerHeight, targetWidth.toFloat(), bannerHeight, linePaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 255, 115)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            val destText = if (instruction.destinationName.isEmpty()) "ĐIỂM ĐẾN" else instruction.destinationName
            canvas.drawText("🎯 ĐÃ ĐẾN NƠI: $destText", 12f, 24f, textPaint)
            return
        }

        // Banner dẫn đường bình thường
        val bgPaint = Paint().apply { color = Color.argb(250, 10, 15, 23) }
        val dividerPaint = Paint().apply {
            color = Color.rgb(0, 224, 255)
            strokeWidth = 2f
        }
        canvas.drawRect(bannerRect, bgPaint)
        canvas.drawLine(0f, bannerHeight, targetWidth.toFloat(), bannerHeight, dividerPaint)

        val distText = "${instruction.formattedDistanceCompact} "
        val actionText = "${instruction.direction.actionPhraseEn} "
        val roadText = instruction.nextRoadName.trim()

        val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 115)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        var x = 12f
        canvas.drawText(distText, x, 25f, greenPaint)
        x += greenPaint.measureText(distText)

        canvas.drawText(actionText, x, 25f, greenPaint)
        x += greenPaint.measureText(actionText)

        if (roadText.isNotEmpty()) {
            canvas.drawText(roadText, x, 25f, whitePaint)
        }
    }

    private fun drawAttribution(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(178, 153, 153, 153)
            textSize = 9f
        }
        val text = clusterMapSource.attribution
        val textWidth = paint.measureText(text)
        canvas.drawText(text, targetWidth - textWidth - 8f, targetHeight - 8f, paint)
    }

    private fun drawFallbackScreen(canvas: Canvas, title: String, subtitle: String, speedKmh: Double) {
        val gridPaint = Paint().apply {
            color = Color.argb(76, 51, 51, 51)
            strokeWidth = 1f
        }
        for (x in 0 until targetWidth step 40) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), targetHeight.toFloat(), gridPaint)
        }
        for (y in 0 until targetHeight step 40) {
            canvas.drawLine(0f, y.toFloat(), targetWidth.toFloat(), y.toFloat(), gridPaint)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 13f
        }
        canvas.drawText(title, 24f, 40f, titlePaint)
        canvas.drawText(subtitle, 24f, 68f, subPaint)
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }
}
