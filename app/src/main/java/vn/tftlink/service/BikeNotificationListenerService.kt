package vn.tftlink.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Service lắng nghe thông báo ứng dụng (Zalo, Messenger, SMS...) để chuyển tiếp lên đồng hồ xe Yamaha
 */
class BikeNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // Sẽ triển khai chi tiết ở Giai đoạn 2 & 4
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
