package ai.a1143.runkarlrun;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One screen: current CPU, the start/stop switch, and the Fat-Cats. */
public class MainActivity extends Activity {

    private static final String SOURCE_URL = "https://github.com/Cid3-TrKd/Run-Karl-Run";
    private static final int REQ_NOTIFICATIONS = 1143;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Cpu cpu = new Cpu();
    private final Map<String, View> rows = new LinkedHashMap<>();

    private TextView cpuValue;
    private TextView cpuCoresLabel;
    private TextView cpuCoresText;
    private TextView stateText;
    private Button toggle;
    private String character;

    private final Runnable cpuTick = new Runnable() {
        @Override
        public void run() {
            renderCpu(cpu.sample());
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cpuValue = findViewById(R.id.cpu_value);
        cpuCoresLabel = findViewById(R.id.cpu_cores_label);
        cpuCoresText = findViewById(R.id.cpu_cores_text);
        stateText = findViewById(R.id.state);
        toggle = findViewById(R.id.toggle);

        character = Prefs.getCharacter(this);
        buildCatRows();
        updateSelection();

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });
        findViewById(R.id.source_link).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
                } catch (Exception ignored) {
                    // No browser installed; the URL is still reachable from the README.
                }
            }
        });

        maybeAskForNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(cpuTick);
        handler.post(cpuTick);
        syncUi();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(cpuTick);
        super.onPause();
    }

    private void buildCatRows() {
        LinearLayout list = findViewById(R.id.cat_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String key : Prefs.CHARACTERS) {
            View row = inflater.inflate(R.layout.row_cat, list, false);
            ImageView image = row.findViewById(R.id.cat_image);
            image.setImageResource(Prefs.imageRes(key));
            image.setContentDescription(getString(Prefs.nameRes(key)));
            ((TextView) row.findViewById(R.id.cat_name)).setText(Prefs.nameRes(key));
            ((TextView) row.findViewById(R.id.cat_eyes)).setText(Prefs.eyesRes(key));
            row.setTag(key);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectCharacter((String) v.getTag());
                }
            });
            list.addView(row);
            rows.put(key, row);
        }
    }

    private void selectCharacter(String key) {
        character = key;
        Prefs.setCharacter(this, key);
        updateSelection();

        if (CatService.running) {
            startService(new Intent(this, CatService.class)
                    .setAction(CatService.ACTION_SET_CHARACTER)
                    .putExtra(CatService.EXTRA_CHARACTER, key));
        }
    }

    private void updateSelection() {
        for (Map.Entry<String, View> entry : rows.entrySet()) {
            boolean selected = entry.getKey().equals(character);
            entry.getValue().setBackgroundResource(
                    selected ? R.drawable.bg_row_selected : R.drawable.bg_row);
            entry.getValue().findViewById(R.id.cat_check)
                    .setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void onToggle() {
        if (CatService.running) {
            stopService(new Intent(this, CatService.class));
            postSync();
            return;
        }

        Intent intent = new Intent(this, CatService.class)
                .setAction(CatService.ACTION_START)
                .putExtra(CatService.EXTRA_CHARACTER, character);
        startForegroundService(intent);
        postSync();
    }

    /** The service updates its static flag on the main thread; give it a moment. */
    private void postSync() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncUi();
            }
        }, 250);
    }

    private void syncUi() {
        boolean running = CatService.running;
        toggle.setText(running ? R.string.stop : R.string.start);
        stateText.setText(running ? R.string.state_running : R.string.state_idle);
    }

    /** Overall percentage, core count, and one number per core. */
    private void renderCpu(Cpu.Sample s) {
        if (s.overall < 0 || s.coreCount() == 0) {
            cpuValue.setText(R.string.cpu_unknown);
            cpuCoresLabel.setText(R.string.cpu_label);
            cpuCoresText.setText(R.string.cpu_cores_unknown);
            return;
        }

        cpuValue.setText(s.overall + "%");
        cpuCoresLabel.setText(getResources().getQuantityString(
                R.plurals.cpu_usage_cores, s.coreCount(), s.coreCount()));
        cpuCoresText.setText(formatCores(s));
    }

    private String formatCores(Cpu.Sample s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.coreCount(); i++) {
            sb.append(String.format(Locale.US, "cpu%d %3d%%", s.coreIds[i], s.cores[i]));
            if (i < s.coreCount() - 1) {
                sb.append(i % 2 == 1 ? '\n' : "    ");
            }
        }
        return sb.toString();
    }

    private void maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.permission_toast, Toast.LENGTH_LONG).show();
        }
    }
}
