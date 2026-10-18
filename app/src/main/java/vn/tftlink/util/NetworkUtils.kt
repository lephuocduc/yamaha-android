package vn.tftlink.util

import android.annotation.SuppressLint
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Tiện ích mạng & cấu hình SSL/TLS tương thích cho Android 9 (API 28) và các dòng máy cũ.
 * Kích hoạt tường minh TLS 1.3 trên Android 9 (nơi TLS 1.3 mặc định bị tắt trong getEnabledProtocols),
 * cho phép kết nối thành công tới các dịch vụ OSM/Valhalla/Photon vốn chỉ chấp nhận TLS 1.3.
 */
object NetworkUtils {

    val trustAllManager: X509TrustManager = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val baseSslSocketFactory: SSLSocketFactory = try {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }.socketFactory
    } catch (_: Exception) {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    /**
     * SocketFactory tùy chỉnh luôn bật tường minh TLS 1.3 nếu nền tảng hỗ trợ.
     * Trên Android 9 (API 28), TLS 1.3 có sẵn trong supportedProtocols nhưng bị tắt trong enabledProtocols.
     */
    class Tls13SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun patchSocket(socket: Socket): Socket {
            if (socket is SSLSocket) {
                try {
                    val supported = socket.supportedProtocols ?: emptyArray()
                    val enabled = (socket.enabledProtocols ?: emptyArray()).toMutableList()
                    if (supported.contains("TLSv1.3") && !enabled.contains("TLSv1.3")) {
                        enabled.add(0, "TLSv1.3")
                    }
                    if (supported.contains("TLSv1.2") && !enabled.contains("TLSv1.2")) {
                        enabled.add("TLSv1.2")
                    }
                    if (enabled.isNotEmpty()) {
                        socket.enabledProtocols = enabled.toTypedArray()
                    }
                } catch (_: Exception) {}
            }
            return socket
        }

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            patchSocket(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            patchSocket(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            patchSocket(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            patchSocket(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            patchSocket(delegate.createSocket(address, port, localAddress, localPort))
    }

    val trustAllSslSocketFactory: SSLSocketFactory = Tls13SocketFactory(baseSslSocketFactory)

    /**
     * Cấu hình OkHttpClient.Builder với TLS 1.3 + TLS 1.2 và toàn bộ cipher suites hợp lệ.
     */
    fun configureSsl(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val modernTlsSpec = try {
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .allEnabledCipherSuites()
                .build()
        } catch (_: Exception) {
            ConnectionSpec.MODERN_TLS
        }

        val compatibleTlsSpec = try {
            ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
                .allEnabledCipherSuites()
                .build()
        } catch (_: Exception) {
            ConnectionSpec.COMPATIBLE_TLS
        }

        return builder
            .connectionSpecs(listOf(modernTlsSpec, compatibleTlsSpec, ConnectionSpec.CLEARTEXT))
            .sslSocketFactory(trustAllSslSocketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
    }

    /**
     * Mở kết nối HttpsURLConnection tương thích với các máy chủ TLS 1.3 và bỏ qua xác thực CA cũ.
     * Sử dụng tương tự như kiến trúc HttpURLConnection của app NConnect gốc.
     */
    fun openHttpsConnection(url: URL): HttpsURLConnection {
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = trustAllSslSocketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        return conn
    }
}
