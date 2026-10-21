package ai.a1143.runkarlrun;

import android.app.Notification;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.os.Build;

/** startForeground() with the special-use type on API 34+, plain on older releases. */
final class Notifier {

    private Notifier() {
    }

    static void startForeground(Service service, int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(id, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            service.startForeground(id, notification);
        }
    }
}
