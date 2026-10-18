package vn.tftlink.service.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Trình nạp và lưu trữ ngói bản đồ OpenStreetMap / CartoDB (OSM Tile Engine)
 */
class OSMTileEngine private constructor(context: Context) {

    companion object {
        const val DEFAULT_CARTO_API_KEY = "cb1_4mz7_1_fb3f57cfec3a69a35eb8be87"
        const val DEFAULT_TILE_URL_TEMPLATE =
            "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=$DEFAULT_CARTO_API_KEY"

        @Volatile
        private var instance: OSMTileEngine? = null

        fun getInstance(context: Context): OSMTileEngine {
            return instance ?: synchronized(this) {
                instance ?: OSMTileEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val memoryCache: LruCache<String, Bitmap>
    private val cacheDirectory: File
    private val httpClient: OkHttpClient

    var tileUrlTemplate: String = DEFAULT_TILE_URL_TEMPLATE
    var currentStyleKey: String = "voyager"

    init {
        // RAM Cache: tối đa 400 ngói bitmap
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8 // 1/8 bộ nhớ RAM khả dụng
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        cacheDirectory = File(context.cacheDir, "OSMTiles_v4")
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }

        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    fun clearAllCaches() {
        memoryCache.evictAll()
        cacheDirectory.deleteRecursively()
        cacheDirectory.mkdirs()
    }

    fun updateStyle(template: String, styleKey: String) {
        this.tileUrlTemplate = template
        this.currentStyleKey = styleKey
        clearMemoryCache()
    }

    private fun styleFolder(): File {
        val dir = File(cacheDirectory, currentStyleKey)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedTile(x: Int, y: Int, z: Int): Bitmap? {
        val key = "${currentStyleKey}_${z}_${x}_$y"
        memoryCache.get(key)?.let { return it }

        val file = File(styleFolder(), "${z}_${x}_$y.png")
        if (file.exists() && file.length() > 1000) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                memoryCache.put(key, bitmap)
                return bitmap
            }
        }
        return null
    }

    suspend fun fetchTile(x: Int, y: Int, z: Int): Bitmap? = withContext(Dispatchers.IO) {
        getCachedTile(x, y, z)?.let { return@withContext it }

        val urlStr = tileUrlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

        val request = Request.Builder()
            .url(urlStr)
            .header("User-Agent", "MyNMAX-Yamaha-Android/1.0 (contact: info@mynmax.app)")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.size > 1000) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val key = "${currentStyleKey}_${z}_${x}_$y"
                            memoryCache.put(key, bitmap)
                            val file = File(styleFolder(), "${z}_${x}_$y.png")
                            FileOutputStream(file).use { it.write(bytes) }
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback: Thử server OpenStreetMap FOSSGIS
            try {
                val fbUrl = "https://tile.openstreetmap.de/$z/$x/$y.png"
                val fbReq = Request.Builder()
                    .url(fbUrl)
                    .header("User-Agent", "MyNMAX-Yamaha-Android/1.0 (contact: info@mynmax.app)")
                    .build()
                httpClient.newCall(fbReq).execute().use { fbRes ->
                    if (fbRes.isSuccessful) {
                        val bytes = fbRes.body?.bytes()
                        if (bytes != null && bytes.size > 1000) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val key = "${currentStyleKey}_${z}_${x}_$y"
                                memoryCache.put(key, bitmap)
                                val file = File(styleFolder(), "${z}_${x}_$y.png")
                                FileOutputStream(file).use { it.write(bytes) }
                                return@withContext bitmap
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext null
    }

    /**
     * Tải trước ngói bản đồ dọc lộ trình để xe chạy tốc độ cao không bị trễ
     */
    fun prefetchRouteTiles(scope: CoroutineScope, coordinates: List<LatLng>, zoom: Int = 17) {
        if (coordinates.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val visitedKeys = HashSet<String>()
            val n = 2.0.pow(zoom)

            for (coord in coordinates) {
                val x = ((coord.longitude + 180.0) / 360.0 * n).toInt()
                val latRad = Math.toRadians(coord.latitude)
                val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
                val key = "${zoom}_${x}_$y"

                if (visitedKeys.add(key)) {
                    if (getCachedTile(x, y, zoom) == null) {
                        fetchTile(x, y, zoom)
                    }
                }
            }
        }
    }
}
