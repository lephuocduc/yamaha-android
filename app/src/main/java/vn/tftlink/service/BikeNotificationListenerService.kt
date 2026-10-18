package vn.tftlink.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vn.tftlink.service.ble.YamahaCCUManager

/**
 * Service lắng nghe thông báo ứng dụng (Zalo, Messenger, SMS, Cuộc gọi...) để chuyển tiếp lên đồng hồ xe Yamaha
 */
class BikeNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ccuManager: YamahaCCUManager? = null

    override fun onCreate() {
        super.onCreate()
        ccuManager = YamahaCCUManager.getInstance(applicationContext, scope)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val pkg = sbn.packageName.lowercase()
        val isCallOrMsg = pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("call") ||
                pkg.contains("zalo") || pkg.contains("messenger") || pkg.contains("whatsapp") ||
                pkg.contains("mms") || pkg.contains("message")

        if (isCallOrMsg && title.isNotBlank()) {
            val alert = if (text.isNotBlank()) "$title: $text" else title
            scope.launch {
                ccuManager?.updateRoadNameOnTFT("TIN NHAN: $alert")
            }
        }
    }
}
