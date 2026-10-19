package vn.tftlink

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Lớp Application khởi tạo toàn cục cho ứng dụng NConnect / MyNMAX.
 * Tích hợp sẵn bộ máy bảo mật Conscrypt (Google BoringSSL) để kích hoạt chuẩn TLS 1.3 (RFC 8446)
 * tương thích 100% với Android 9 (API 28) và các dòng máy cũ khi kết nối tới các dịch vụ OSM/Valhalla.
 */
class NConnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Log.i("NConnectApplication", "BoringSSL Conscrypt Provider installed successfully (TLS 1.3 enabled)")
        } catch (e: Throwable) {
            Log.w("NConnectApplication", "Failed to insert Conscrypt provider: ${e.message}")
        }
    }
}
