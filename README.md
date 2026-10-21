# Run-Karl-Run

A fat cat that runs to your CPU.

The cat lives in your status bar and runs faster as your phone works harder. Open
the app and you get the numbers behind the animation: overall CPU usage and a
per-core breakdown, live.

No ads. No trackers. No network permission at all — the app cannot reach the
internet, so there is nothing to send anywhere.

![Run-Karl-Run main screen](docs/screen-main.png)

## What it does

- **Status-bar cat.** A foreground service keeps a small cat icon in the status
  bar and redraws it frame by frame. Its gait follows real CPU usage in five
  discrete steps.
- **Live CPU readout.** While the app is open it samples once a second and shows
  the overall percentage, the core count, and every core's own percentage.
- **Six Fat-Cats.** Pick the one that runs for you. They are the same cat in
  different colourways, and the one you pick is what appears in the notification
  shade at full size.

| Fat-Cat | Colourway |
|---|---|
| **Karl** | black · yellow eyes |
| Marshmallow | white · black eyes |
| Pebble | grey · black eyes |
| Blueberry | blue · yellow eyes |
| Pumpkin | orange · yellow eyes |
| Patches | calico · yellow eyes |

The status-bar icon itself is tinted to a single colour by Android — that is the
platform's rule for small icons, not a limitation of the app — so the colourways
show in the app and as the large icon in the shade.

## The five gaits

Five steps, not a continuous blend, so a change in load is something you can
actually see. The bands meet between the load points that matter, and every
boundary has a deadband of 5 CPU points: the reading is sampled once a second and
wobbles, and without the deadband a load sitting on a boundary would flicker the
cat between two speeds. With it, a load hovering on a boundary kept its gear for
the whole trace.

| gear | gait | CPU | pose | poses per update | updates/s | poses/s | stride |
|---|---|---|---|---|---|---|---|
| 1 | walk | below 28% | 386 ms | 1 | 2.59 | 2.59 | 3.09 s |
| 2 | trot | 28–47% | 320 ms | 1 | 3.13 | 3.13 | 2.56 s |
| 3 | canter | 47–67% | 250 ms | 1 | 4.00 | 4.00 | 2.00 s |
| 4 | gallop | 67–89% | 160 ms | **2** | 3.13 | 6.25 | 1.28 s |
| 5 | bolt | 89% and up | 125 ms | **2** | 4.00 | 8.00 | 1.00 s |

The two fastest gaits advance **two poses per update** — animating "on twos" —
because the notification itself cannot be redrawn fast enough to animate them one
pose at a time. See below for why.

## How CPU is measured

Mostly from `/proc/stat`, using deltas of the per-core `cpuN` lines between
samples. Some Android builds deny apps that file, so there is a fallback that
reads the kernel's per-core cpuidle residency counters and turns idle time into
usage. Whichever path is live, the number on screen and the cat's gait come from
the same sample, so they always agree.

## The platform limit that shaped the animation

Android caps notification updates per package at roughly 5 per second and
**drops** the excess rather than queueing it. Dropped frames scramble the order
of the animation, which reads as a frozen cat. So the app deliberately posts
slower than that cap and gets its speed from poses-per-update instead — the
classic animation trick of doubling apparent speed at the same framerate.

Being precise about that ceiling took measurement, and the interesting part is
that **it is not a one-second average.** At a 225 ms cadence the app posted 79 of
86 updates with exactly 5 in the trailing second — 5.0/s on paper — and still had
one update shed every five seconds, with the system reporting rates of 5.65–6.32/s.
Reconstructing those reports from the same post timings puts the effective window
at roughly 700–800 ms: four posts inside that window is what trips it. So a
320 ms cadence (4 posts spanning 960 ms) runs clean, and 250 ms (4 posts in
750 ms) sits just over the line.

The floor is 250 ms because the five gaits need that rate to stay distinct —
from 2.59 to 8.00 poses per second. The cost is one dropped update every ~5
seconds on the two gaits that post at 250 ms, which shows up as a single skipped
pose. If you would rather have a perfectly even animation than five clearly
different speeds, raise `MIN_POST_MS` in `CatService.java` to 320: the shedding
stops, and trot and canter collapse into nearly the same speed.

All of those numbers are measured on a Galaxy A26 5G running Android 16, from
logcat post timings, with a `yes`-loop load and `/proc/stat` cross-checked
against the app's own reading.

## Permissions

```
android.permission.POST_NOTIFICATIONS
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
```

That is the whole list. **No `INTERNET`, no `READ_*`, no `WRITE_*`, no location,
no analytics, no ad SDK.** The source link in the app opens your browser; the app
cannot open a socket itself.

Stopping matters and is easy: the STOP button in the app, or the Stop action on
the notification. Nothing restarts itself.

## Build

No Gradle, no AndroidX — a plain framework app built with the SDK tools:

```sh
# needs: a JDK, and Android SDK build-tools + platform android-36
KEYSTORE=/path/to/your.jks KEYSTORE_PASS=... KEY_ALIAS=... ./build.sh
```

`build.sh` runs `aapt2 → javac → d8 → zipalign → apksigner` and writes
`dist/RunKarlRun-1.0.apk`. The art in `app/src/main/res/` is generated by
`art/gen_art.py` from `art/ghost-karl.jpeg`; `art/preview/sheet.png` shows the
whole set at a glance.

## Install

Download the APK from [Releases](../../releases) and open it. Android will warn
about installing from outside the Play Store — that is expected for an unsigned-
by-Google build. Android 13+ will ask for notification permission on first run;
without it the cat has no status bar to run in.

## Licence

MIT — see [LICENSE](LICENSE).

This is an independent, clean-room app. It is not affiliated with, derived from,
or endorsed by any other cat-on-a-status-bar app.
