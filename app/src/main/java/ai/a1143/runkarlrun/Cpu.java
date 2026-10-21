package ai.a1143.runkarlrun;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;

/**
 * CPU usage, aggregate and per core.
 *
 * <p>Primary source: deltas from /proc/stat. Some Android builds deny app
 * processes that file (SELinux labels it proc_stat), so the fallback is the
 * per-core cpuidle counters: {@code 100 * (1 - idle_time / wall_time)}. On the
 * SM-S266V the fallback was measured against /proc/stat per core and tracks it
 * to within a few percent.
 */
public final class Cpu {

    private static final String TAG = "RunKarlRun";
    private static final int MAX_CORES = 16;

    /** One reading. */
    public static final class Sample {
        /** 0..100, or -1 when nothing could be read. */
        public final int overall;
        /** The cpuN index for each entry in {@link #cores}. */
        public final int[] coreIds;
        /** Per-core usage, 0..100, parallel to {@link #coreIds}. */
        public final int[] cores;

        Sample(int overall, int[] coreIds, int[] cores) {
            this.overall = overall;
            this.coreIds = coreIds;
            this.cores = cores;
        }

        public int coreCount() {
            return coreIds.length;
        }
    }

    // ---- /proc/stat state
    private boolean procStatOk = true;
    private boolean warned = false;
    private long lastTotal = -1L;
    private long lastIdle = -1L;
    private final long[] lastCoreTotal = new long[MAX_CORES];
    private final long[] lastCoreIdle = new long[MAX_CORES];

    // ---- cpuidle state
    private long lastWallUs = -1L;
    private final long[] lastIdleSum = new long[MAX_CORES];
    private int lastIdleCores = -1;

    public Sample sample() {
        if (procStatOk) {
            Sample s = sampleProcStat();
            if (s != null) return s;
            procStatOk = false;
            if (!warned) {
                warned = true;
                Log.w(TAG, "CPU: /proc/stat unavailable, using cpuidle counters");
            }
        }
        Sample s = sampleCpuIdle();
        if (s != null) return s;
        return new Sample(-1, new int[0], new int[0]);
    }

    // ------------------------------------------------------------- /proc/stat

    private Sample sampleProcStat() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/stat"), 512);

            long total = -1L;
            long idle = -1L;
            long[] totals = new long[MAX_CORES];
            long[] idles = new long[MAX_CORES];
            int[] ids = new int[MAX_CORES];
            int n = 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("cpu ")) {
                    long[] v = parseStat(line);
                    if (v == null) return null;
                    total = v[0];
                    idle = v[1];
                } else if (line.startsWith("cpu")) {
                    int sp = line.indexOf(' ');
                    if (sp < 4) continue;
                    int id;
                    try {
                        id = Integer.parseInt(line.substring(3, sp));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (id < 0 || id >= MAX_CORES) continue;
                    long[] v = parseStat(line);
                    if (v == null) continue;
                    ids[n] = id;
                    totals[n] = v[0];
                    idles[n] = v[1];
                    n++;
                } else {
                    break; // the cpu lines come first in /proc/stat
                }
            }

            if (total < 0L || n == 0) return null;

            boolean primed = lastTotal >= 0L;
            int[] cores = new int[n];
            int sum = 0;
            for (int i = 0; i < n; i++) {
                int id = ids[i];
                long dt = totals[i] - lastCoreTotal[id];
                long di = idles[i] - lastCoreIdle[id];
                int pct = primed ? percent(dt - di, dt) : 0;
                cores[i] = pct;
                sum += pct;
            }

            int overall;
            if (primed && total > lastTotal) {
                overall = percent((total - lastTotal) - (idle - lastIdle), total - lastTotal);
            } else {
                overall = primed ? sum / n : 0;
            }

            for (int i = 0; i < n; i++) {
                lastCoreTotal[ids[i]] = totals[i];
                lastCoreIdle[ids[i]] = idles[i];
            }
            lastTotal = total;
            lastIdle = idle;

            return new Sample(overall, Arrays.copyOf(ids, n), cores);
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
        }
    }

    private static long[] parseStat(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 5) return null;
        long total = 0L;
        int upto = Math.min(8, p.length - 1); // user..steal; guest is a subset
        for (int i = 1; i <= upto; i++) {
            total += Long.parseLong(p[i]);
        }
        long idle = Long.parseLong(p[4]);
        if (p.length > 5) idle += Long.parseLong(p[5]);
        return new long[]{total, idle};
    }

    // ---------------------------------------------------------------- cpuidle

    private Sample sampleCpuIdle() {
        long nowUs = SystemClock.elapsedRealtime() * 1000L;

        long[] sums = new long[MAX_CORES];
        int[] ids = new int[MAX_CORES];
        int n = 0;
        for (int c = 0; c < MAX_CORES; c++) {
            long s = readIdleSum(c);
            if (s < 0L) continue;
            ids[n] = c;
            sums[n] = s;
            n++;
        }
        if (n == 0) return null;

        long wall = nowUs - lastWallUs;
        boolean primed = lastWallUs > 0L && lastIdleCores == n && wall > 0L;

        int[] cores = new int[n];
        long idleDelta = 0L;
        for (int i = 0; i < n; i++) {
            long d = primed ? sums[i] - lastIdleSum[i] : 0L;
            if (d < 0L) d = 0L;
            idleDelta += d;
            cores[i] = primed ? percent(wall - d, wall) : 0;
        }

        int overall = primed ? percent(wall * n - idleDelta, wall * n) : 0;

        System.arraycopy(sums, 0, lastIdleSum, 0, n);
        lastIdleCores = n;
        lastWallUs = nowUs;

        return new Sample(overall, Arrays.copyOf(ids, n), cores);
    }

    private static long readIdleSum(int core) {
        long sum = 0L;
        boolean any = false;
        for (int state = 0; state < 4; state++) {
            long v = readLong("/sys/devices/system/cpu/cpu" + core
                    + "/cpuidle/state" + state + "/time");
            if (v < 0L) break;
            sum += v;
            any = true;
        }
        return any ? sum : -1L;
    }

    private static long readLong(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path), 24);
            String line = r.readLine();
            return line == null ? -1L : Long.parseLong(line.trim());
        } catch (Exception e) {
            return -1L;
        } finally {
            close(r);
        }
    }

    // ------------------------------------------------------------------ maths

    /** busy/total as 0..100, with both ends clamped. */
    private static int percent(long busy, long total) {
        if (total <= 0L) return 0;
        long b = busy;
        if (b < 0L) b = 0L;
        if (b > total) b = total;
        return (int) Math.round(100.0 * b / total);
    }

    private static void close(BufferedReader r) {
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }
}
