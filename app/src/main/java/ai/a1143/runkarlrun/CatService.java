package ai.a1143.runkarlrun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

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

    private static final String TAG = "RunKarlRun";
    private static final boolean DEBUG = false;

    /** Read by MainActivity to keep its button honest. Same process, same class loader. */
    public static volatile boolean running = false;

    private static final String CHANNEL_ID = "running_cat";
    private static final int NOTIFICATION_ID = 1143;

    /**
     * CPU is sampled 2.5 times a second so the cat reacts inside a single stride.
     * At the old 1 s cadence the cat could not start moving until a stride after
     * the load had already climbed.
     */
    private static final long SAMPLE_MS = 400L;

    /**
     * Weight given to a new sample. 0.55 at 400 ms is a ~0.5 s time constant:
     * fast enough to feel immediate in both directions -- the complaint that
     * prompted this was a cat that lagged the load coming *down* -- and slow
     * enough not to twitch on one noisy reading.
     */
    private static final double LOAD_ALPHA = 0.55;

    /**
     * Twelve poses: two six-pose strides, with the lead legs swapped between them.
     * The loop holds two strides on purpose -- the stride, not the frame count, is
     * the speed control (see STRIDE_BOLT_MS), and two of them split the loop into
     * halves that mirror rather than repeat, so the same silhouette never comes
     * back twice in a row.
     */
    private static final int[] FRAMES = {
            R.drawable.cat_run_0, R.drawable.cat_run_1, R.drawable.cat_run_2, R.drawable.cat_run_3,
            R.drawable.cat_run_4, R.drawable.cat_run_5, R.drawable.cat_run_6, R.drawable.cat_run_7,
            R.drawable.cat_run_8, R.drawable.cat_run_9, R.drawable.cat_run_10,
            R.drawable.cat_run_11
    };

    // Android drops notification updates above ~3.5 per second, and a dropped
    // update is not queued -- that pose is simply never shown.
    //
    // The estimate is an EWMA of the gap between enqueues
    // (android.service.notification.RateEstimator, alpha 0.7), and the check runs
    // *after* the estimate has already been updated for the enqueue being tested:
    // mUsageStats.registerEnqueuedByAppAndAccepted() is called early in
    // enqueueNotificationInternal(), the rate check is later in postNotification().
    // So the "time since the last event" term is ~0 and the test collapses to
    //
    //     1 / (0.7 * average_gap) > 5        <=>        average_gap < 285.7 ms
    //
    // i.e. anything faster than ~3.5 posts/s gets shed. Measured on this phone at a
    // 270 ms cadence (3.70/s): 71 enqueues and 44 rate violations in 20 s, so 62% of
    // the frames were dropped and the cat advanced at 1.35 *visible* updates per
    // second while the app believed it was posting 3.70. 300 ms (3.33/s, an
    // estimated rate of 4.76) has run clean in every test, so 300 ms is the floor.
    private static final int POST_FAST_MS = 300;

    /** The slowest post cadence. A walk does not need three updates a second. */
    private static final int POST_SLOW_MS = 700;

    /**
     * Stride length -- one full leg cycle -- at idle and at a pegged CPU. Load
     * maps linearly between the two, so 50% lands at 1.33 s and there is nothing
     * to memorise.
     *
     * <p>450 ms is 2.2 strides a second, which is a galloping cat. It is not
     * faster for a structural reason rather than for taste: the platform will not
     * deliver an update faster than one per 300 ms, so at 450 ms a stride is only
     * one and a half updates long. Well below that the cat would be showing two
     * poses and no motion.
     */
    private static final int STRIDE_IDLE_MS = 2200;
    private static final int STRIDE_BOLT_MS = 450;

    /** Poses in one stride of the art. The 12-frame loop holds two of them. */
    private static final int STRIDE_FRAMES = 6;
    private static final int CYCLE_FRAMES = STRIDE_FRAMES * 2;

    /**
     * Poses a post should advance at a normal pace. Two is the animation
     * convention known as "on twos": smooth at this size, and half the wakeups of
     * showing every pose.
     */
    private static final int POSES_PER_POST = 2;

    /** Gait words, chosen from the stride actually on screen (see gaitFor). */
    private static final int[] GAIT_NAME = {
            R.string.gait_walk, R.string.gait_trot, R.string.gait_canter,
            R.string.gait_gallop, R.string.gait_bolt
    };

    private HandlerThread thread;
    private Handler handler;

    private final Cpu cpu = new Cpu();

    // Animation state. Everything below is touched only from the handler thread,
    // except `character`, which onStartCommand also writes from the main thread.
    private int frame = 0;
    private int gait = 0;
    /** Position in the 12-frame loop, 0..1. Derived from the clock, not from posts. */
    private double phase = 0.0;
    private long lastPostAt = 0L;
    private int load = 0;
    private long lastSampleAt = 0L;
    private long nextPostAt = 0L;
    private volatile String character = Prefs.DEFAULT_CHARACTER;

    private PendingIntent stopPi;
    private PendingIntent openPi;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            final long now = SystemClock.uptimeMillis();

            if (now - lastSampleAt >= SAMPLE_MS) {
                Cpu.Sample s = cpu.sample();
                if (s.overall >= 0) {
                    load = (int) Math.round(load + (s.overall - load) * LOAD_ALPHA);
                }
                lastSampleAt = now;
            }

            // The cat follows the smoothed load, and the same figure is the one
            // printed, so the percentage in the text predicts how fast the cat is
            // going.
            final int stride = strideFor(load);

            // Post often enough to advance about POSES_PER_POST poses, no faster
            // than the platform accepts and no slower than a walk needs. This is a
            // display rate only -- the stride is set by the clock below, so a post
            // the system sheds costs nothing but a slightly longer jump on the
            // next one.
            // P poses per post needs an interval of P/6 of a stride, because a
            // stride is six of the twelve frames.
            final int iv = clamp(stride * POSES_PER_POST / STRIDE_FRAMES,
                    POST_FAST_MS, POST_SLOW_MS);

            // The pose is derived from elapsed time, not accumulated per post. That
            // is what makes the stride exact. With an integer step the stride could
            // only ever be a whole number of posts long, so a 2.1 s walk and a
            // 0.45 s sprint could not both be hit and every change of step made the
            // cat lurch; and a late post left the animation permanently behind
            // instead of self-correcting. Advancing a fraction of the loop per
            // millisecond, both problems disappear.
            phase += (double) (now - lastPostAt) / (2.0 * stride);
            phase -= Math.floor(phase);
            lastPostAt = now;
            frame = Math.min(CYCLE_FRAMES - 1, (int) (phase * CYCLE_FRAMES));
            gait = gaitFor(stride);

            postNotification();

            // Absolute deadline, not postDelayed(this, iv). Re-arming a relative
            // delay after the work completes makes the real interval iv + queuing +
            // binder time, so the animation slows down exactly when the phone is
            // busiest -- the opposite of what a CPU meter should do. Measured on
            // this phone under 8x `yes`: 2.19 posts/s against a 4.00/s target, p90
            // gap 914 ms and a worst gap of 1603 ms.
            long next = nextPostAt + iv;
            if (next <= now) {
                // More than one interval behind: a binder stall, a GC pause, or an
                // update the system shed. Do not fire a burst to catch up -- extra
                // posts would push the package over the shed rate and cost more
                // frames than they recover. Skip the slots instead -- the pose is
                // already derived from the clock, so it lands in the right place
                // without any catch-up arithmetic.
                final long skipped = (now - next) / iv + 1L;
                next += skipped * iv;
                if (DEBUG) Log.d(TAG, "cat: late " + (now - nextPostAt) + " ms, skipped " + skipped);
            }
            nextPostAt = next;
            if (running) handler.postAtTime(this, nextPostAt);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        // A dedicated looper at display priority. On the main looper the tick
        // competes with everything the app and the notification shade do, which is
        // what stretched the interval under load; this thread only runs the tick.
        thread = new HandlerThread("runkarlrun-cat", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());

        // Built once. Re-creating these on every tick is two extra binder round trips
        // per frame, and the posts run at up to 3.3 per second.
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
            running = false;
            handler.removeCallbacks(tick);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_CHARACTER.equals(action)) {
            String next = intent.getStringExtra(EXTRA_CHARACTER);
            if (next != null) {
                character = next;
                Prefs.setCharacter(this, next);
                // Repaint from the handler thread, so `frame`, `gait` and `load`
                // are only ever read by the thread that writes them.
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) postNotification();
                    }
                });
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
        lastPostAt = lastSampleAt;
        handler.removeCallbacks(tick);
        handler.post(tick);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Stride length in ms for a load reading: linear, 2.2 s idle to 450 ms pegged. */
    private static int strideFor(int pct) {
        final int p = clamp(pct, 0, 100);
        return STRIDE_IDLE_MS - (STRIDE_IDLE_MS - STRIDE_BOLT_MS) * p / 100;
    }

    /**
     * Gait word for the stride actually being drawn, so the label and the picture
     * cannot disagree. The bands are the ones in strings.xml and were picked from
     * real stride lengths: a walking cat is about 2 s per stride, a trot a little
     * over 1 s, a gallop under 0.6 s.
     */
    private static int gaitFor(int strideMs) {
        if (strideMs >= 1600) return 0;     // walk
        if (strideMs >= 1150) return 1;     // trot
        if (strideMs >= 800) return 2;      // canter
        if (strideMs >= 560) return 3;      // gallop
        return 4;                           // bolt
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void postNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
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

    /**
     * <p>The percentage printed is the smoothed figure the cat is running at, not
     * the raw sample behind it. Printing the raw one made the text and the cat
     * disagree for about a second whenever the load moved -- a 41% reading beside
     * a walking cat, which reads as a bug even though both numbers were right
     * about different things. One number, used for both, cannot disagree with
     * itself.
     */
    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(FRAMES[frame])
                // A resource id, not the 256 px bitmap: setLargeIcon(Bitmap) serialises
                // the whole image through binder on every single post, and this post
                // happens up to 3.3 times a second. The resource id is an int.
                .setLargeIcon(Icon.createWithResource(this, Prefs.imageRes(character)))
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text,
                        Math.max(0, load),
                        getString(Prefs.nameRes(character)),
                        getString(GAIT_NAME[gait])))
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
