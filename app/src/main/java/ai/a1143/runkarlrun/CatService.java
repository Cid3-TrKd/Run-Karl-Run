package ai.a1143.runkarlrun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Keeps a cat running in the status bar. The animation speed follows CPU usage:
 * idle is a slow walk, full load is a sprint. The system forces status-bar icons
 * to a single tint, so the silhouette is what moves up there; the chosen
 * colourway is shown as the notification's large icon.
 */
public class CatService extends Service {

    public static final String ACTION_START = "ai.a1143.runkarlrun.action.START";
    public static final String ACTION_STOP = "ai.a1143.runkarlrun.action.STOP";
    public static final String ACTION_SET_CHARACTER = "ai.a1143.runkarlrun.action.SET_CHARACTER";
    public static final String EXTRA_CHARACTER = "character";

    /** Read by MainActivity to keep its button honest. Same process, same class loader. */
    public static volatile boolean running = false;

    private static final String CHANNEL_ID = "running_cat";
    private static final int NOTIFICATION_ID = 1143;

    private static final int[] FRAMES = {
            R.drawable.cat_run_0, R.drawable.cat_run_1, R.drawable.cat_run_2, R.drawable.cat_run_3,
            R.drawable.cat_run_4, R.drawable.cat_run_5, R.drawable.cat_run_6, R.drawable.cat_run_7,
            R.drawable.cat_run_8, R.drawable.cat_run_9, R.drawable.cat_run_10,
            R.drawable.cat_run_11
    };

    // The gait follows the overall CPU percentage, and two measured limits shape it.
    //
    // First, Android itself: NotificationManagerService sheds notification updates
    // above 5 per second per package ("Shedding notify (update) ... rate limit
    // (5.0) exceeded"), and shed frames are dropped, not queued, which scrambles
    // the pose order and reads as a frozen cat.
    //
    // The limit is not a one-second average, which makes it easy to trip while
    // looking innocent. Measured on this phone: at a 225 ms cadence the app posted
    // 79 of 86 updates with exactly 5 in the trailing second -- 5.0/s on paper, and
    // it still shed once every ~5 s, reporting rates of 5.67-6.32/s. Reconstructing
    // those reports from the same log puts them at a ~600-700 ms window (4 posts in
    // 700 ms = 5.71/s), so the effective ceiling is nearer 3.5-4 updates per second
    // than 5. A 320 ms cadence (3.13/s) ran clean across a full load test. The floor
    // is therefore 250 ms = 4.0/s, the fastest cadence that still leaves margin:
    // anything above ~233 ms keeps a 700 ms window down to 3 posts.
    //
    // Second, that ceiling would make a real sprint impossible if the pose rate were
    // tied to the posting rate. It is not: where the pose interval falls below the
    // posting floor the cat advances two poses per update ("on twos"), which is how
    // hand-drawn animation doubles apparent speed without doubling the framerate.
    private static final int MIN_POST_MS = 250;      // 4.00 posts/s, clear of the shed zone

    // Five gaits, one per CPU band. The anchors are this phone's real load points --
    // ~18% idle, 57% with four busy cores, 100% pegged -- plus the two intermediates
    // between them, at 37% and 78%. Bands meet halfway between anchors, and each
    // boundary carries GEAR_HYST points of deadband: the CPU is sampled once a second
    // and wobbles by a few points, so without hysteresis a load sitting on a boundary
    // would flicker the cat between two speeds. Simulated against +-4% noise: a
    // deadband of 3 flipped gear 16.5 times per two minutes, 5 flipped it zero times,
    // and both still climb from walk to bolt in a single sample on sustained load.
    //
    // Pose intervals are near-geometric and constrained from below: a gear that
    // posts one pose at a time cannot be faster than the posting floor (250 ms), and
    // the two fast gears get past it by advancing two poses per post instead. So
    // walk/trot/canter are posting-rate limited (2.59, 3.13, 4.00 poses/s) and
    // gallop/bolt are on twos (6.25 and 8.00 poses/s posted at 3.13 and 4.00/s).
    // Cycle times come out 3.09, 2.56, 2.00, 1.28, 1.00 s.
    private static final int[] GEAR_UP = {15, 35, 55, 75};   // CPU% that enters gear i+1
    private static final int GEAR_HYST = 2;                  // deadband, CPU points
    private static final int[] GEAR_POSE_MS = {600, 400, 250, 150, 80};
    private static final int[] GEAR_NAME = {
            R.string.gait_walk, R.string.gait_trot, R.string.gait_canter,
            R.string.gait_gallop, R.string.gait_bolt
    };

    private HandlerThread thread;
    private Handler handler;
    private final Cpu cpu = new Cpu();

    private int frame = 0;
    private int gear = 0;
    private int usage = 0;
    private long lastSampleAt = 0L;
    private long nextPostAt = 0L;
    private String character = Prefs.DEFAULT_CHARACTER;
    private PendingIntent stopPi;
    private PendingIntent openPi;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (now - lastSampleAt >= 400L) {
                Cpu.Sample s = cpu.sample();
                if (s.overall >= 0) usage = s.overall;
                gear = gearFor(usage);
                lastSampleAt = now;
            }

            int pose = GEAR_POSE_MS[gear];
            int step = Math.max(1, (int) Math.ceil(MIN_POST_MS / (double) pose));
            int iv = pose * step;

            frame = (frame + step) % FRAMES.length;
            postNotification();

            long next = nextPostAt + iv;
            if (next <= now) {
                final long skipped = (now - next) / iv + 1L;
                next += skipped * iv;
            }
            nextPostAt = next;
            handler.postAtTime(this, next);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        thread = new HandlerThread("runkarlrun-cat", Process.THREAD_PRIORITY_FOREGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());

        // Built once. Re-creating these on every tick is two extra binder round trips
        // per frame, and the posts run at up to 4.8 per second.
        stopPi = PendingIntent.getBroadcast(this, 0,
                new Intent(this, StopReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (action == null) action = ACTION_START;

        if (ACTION_STOP.equals(action)) {
            handler.removeCallbacks(tick);
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_CHARACTER.equals(action)) {
            String next = intent.getStringExtra(EXTRA_CHARACTER);
            if (next != null) {
                character = next;
                Prefs.setCharacter(this, next);
                if (running) {
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
                }
            }
            return START_STICKY;
        }

        // ACTION_START, or a system restart with a null intent.
        String requested = intent != null ? intent.getStringExtra(EXTRA_CHARACTER) : null;
        character = requested != null ? requested : Prefs.getCharacter(this);
        Prefs.setCharacter(this, character);

        Notifier.startForeground(this, NOTIFICATION_ID, buildNotification());

        running = true;
        cpu.sample();               // seed the delta so the first tick is real
        lastSampleAt = SystemClock.uptimeMillis();
        nextPostAt = lastSampleAt;
        handler.removeCallbacks(tick);
        handler.post(tick);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Picks one of the five gaits for this CPU reading. The deadband means a load
     * sitting on a boundary keeps the gear it already had, so the cat does not
     * stutter between two speeds while the percentage wobbles.
     */
    private int gearFor(int pct) {
        int g = gear;
        while (g < GEAR_UP.length && pct >= GEAR_UP[g] + GEAR_HYST) g++;
        while (g > 0 && pct < GEAR_UP[g - 1] - GEAR_HYST) g--;
        return g;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void postNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private int colorFor(String key) {
        switch (key) {
            case "black":   return 0xFF222222;
            case "white":   return 0xFFFFFFFF;
            case "grey":    return 0xFF888888;
            case "blue":    return 0xFF4488FF;
            case "calico":  return 0xFFCC8844;
            case "orange":
            default:        return 0xFFFF8800;
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(FRAMES[frame])
                // A resource id, not the 256 px bitmap: setLargeIcon(Bitmap) serialises
                // the whole image through binder on every single post, and this post
                // happens up to 16 times a second. The resource id is an int.
                .setLargeIcon(Icon.createWithResource(this, Prefs.imageRes(character)))
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text,
                        Math.max(0, usage),
                        getString(Prefs.nameRes(character)),
                        getString(GEAR_NAME[gear])))
                .setContentIntent(openPi)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop),
                        getString(R.string.stop), stopPi).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
