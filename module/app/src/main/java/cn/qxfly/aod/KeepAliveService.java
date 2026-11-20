package cn.qxfly.aod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * Optional foreground service that pins the app process.
 *
 * <p>Why: the app process holds the only full-LRC SuperLyric registration and
 * hosts the state provider. MIUI/HyperOS freezes or kills background processes
 * aggressively, which used to drop the full document (native injection then
 * degraded to a single line). Users who care can enable this switch; the
 * notification is low-importance and silent.
 *
 * <p>Off by default: a persistent notification should be the user's choice.
 */
public final class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "aodlyric_keepalive";
    private static final int NOTIFICATION_ID = 0x5A0D;

    /** Same SharedPreferences file the UI settings use. */
    private static final String PREFS = "aodlyric_ui_prefs";
    private static final String KEY_ENABLED = "keep_alive_enabled";

    public static boolean isEnabled(@Nullable Context context) {
        if (context == null) {
            return false;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(@Nullable Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, enabled).apply();
        } catch (Throwable ignored) {
        }
        if (enabled) {
            start(context);
        } else {
            stop(context);
        }
    }

    public static void start(@Nullable Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            AodLog.w("keep-alive start failed: " + t);
        }
    }

    public static void stop(@Nullable Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            AodLog.w("startForeground failed: " + t);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Both the process pin and the publisher live here: the service may be
        // restarted by the system long after the activity is gone.
        LyricPublisher.get().ensureRegistered(this);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.keepalive_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        // Launch through the package manager instead of referencing
        // MainActivity: this class is compiled in the base javac pass, the
        // activity only in the Compose pass.
        android.content.Intent launch =
                getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            launch = new android.content.Intent();
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_lyric)
                .setContentTitle(getString(R.string.keepalive_title))
                .setContentText(getString(R.string.keepalive_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
