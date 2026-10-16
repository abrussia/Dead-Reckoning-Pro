package nisargpatel.deadreckoning.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.activity.MainContainerActivity;

/**
 * Keeps the app alive while a track is being recorded: a foreground notification plus a
 * partial wake lock, so the step and orientation sensors keep working with the screen off
 * (for example with the phone in a pocket underground). The tracking itself still runs in
 * the map screen; this service only stops Android from pausing or killing it.
 */
public class RecordingService extends Service {

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 4711;
    private static final long MAX_WAKE_MS = 12L * 60 * 60 * 1000; // safety limit: 12 h

    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, RecordingService.class));
        } catch (Exception ignored) {
            // e.g. not allowed to start from background; tracking still works in the foreground
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, RecordingService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeadReckoning:recording");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(MAX_WAKE_MS);
            }
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainContainerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_directions_walk)
                .setContentTitle(getString(R.string.recording_title))
                .setContentText(getString(R.string.recording_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
