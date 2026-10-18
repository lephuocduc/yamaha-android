package vn.tftlink.util

import android.annotation.SuppressLint
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Tiện ích mạng & cấu hình SSL/TLS tương thích cho Android 9 (API 28) và các dòng máy cũ.
 * Khắc phục lỗi SSLHandshakeException khi kết nối tới các dịch vụ OSM/Valhalla/Photon sử dụng
 * chuỗi chứng chỉ Let's Encrypt ECDSA (ISRG Root X2 / Root YE).
 */
object NetworkUtils {

    val trustAllManager: X509TrustManager = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val trustAllSslSocketFactory: SSLSocketFactory = try {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }.socketFactory
    } catch (_: Exception) {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    /**
     * Cấu hình OkHttpClient.Builder với TLS hiện đại + dự phòng tương thích và bỏ qua lỗi Root CA cũ của Android.
     */
    fun configureSsl(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .sslSocketFactory(trustAllSslSocketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
    }
}
