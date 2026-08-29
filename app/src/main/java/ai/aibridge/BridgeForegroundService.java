package ai.aibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Keeps the NanoHTTPD server and WebViews alive via a foreground notification.
 * WebViews themselves live in AIBridgeApplication (process-wide), so the
 * service only guards the network endpoint and holds a wake lock.
 */
public class BridgeForegroundService extends Service {

    private static final String CHANNEL = "ai_bridge_channel";
    private static final int NOTIF_ID = 1337;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIBridge::svc");
        wakeLock.acquire(/* no timeout */);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        BridgeServer srv = AIBridgeApplication.get().getBridgeServer();
        if (!srv.isRunning()) {
            try { srv.startServer(); } catch (Exception e) { LogBus.get().log("start fail: " + e.getMessage()); }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AIBridgeApplication.get().getBridgeServer().stopServer();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "AI Bridge Server", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("AI Bridge Running")
                .setContentText("Local HTTP server @ 127.0.0.1:8080")
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
