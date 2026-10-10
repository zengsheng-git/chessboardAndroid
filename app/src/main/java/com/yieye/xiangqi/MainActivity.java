package com.yieye.xiangqi;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ChessLogic";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private TextView resultTextView;
    private android.view.View permissionLayout;
    private Button btnStartService;
    private android.widget.Spinner spinnerDepth;
    private android.widget.Spinner spinnerHint;
    private static final String PREFS_NAME = "ChessPrefs";
    private static final String KEY_DEPTH = "calc_depth";
    private static final String KEY_HINT = "hint_level";
    // 提示强度档位：0=不限，1~5 对应固定搜索深度 2/4/6/8/12（弱化提示，练棋用）。
    // 标签里的"约业几"为自对弈实测推算的参考段位（天天象棋口径），非官方校准
    private static final String[] HINT_LABELS = {
            "不限（最强）", "新手（约业2-3）", "入门（约业4-5）",
            "初级（约业5-6）", "中级（约业7-8）", "高级（约业9+）"
    };

    private BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String displayStr = intent.getStringExtra("displayStr");
            if (displayStr != null) {
                runOnUiThread(() -> resultTextView.append("\n" + displayStr));
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        resultTextView = findViewById(R.id.resultTextView);
        permissionLayout = findViewById(R.id.permissionLayout);
        Button btnOpenPermission = findViewById(R.id.btnOpenPermission);
        btnStartService = findViewById(R.id.btnStartService);
        spinnerDepth = findViewById(R.id.spinnerDepth);
        Button btnBatterySetting = findViewById(R.id.btnBatterySetting);

        initDepthSpinner();
        initHintSpinner();
        initBoardMirrorToggle();

        btnBatterySetting.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        });

        btnOpenPermission.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 100);
        });
//“本应用使用屏幕捕获功能识别象棋棋盘，仅在用户主动开启时运行，不会收集或上传个人数据。”
        btnStartService.setOnClickListener(v -> {
            if (isServiceRunning(AnalysisService.class)) {
                stopService(new Intent(this, AnalysisService.class));
                btnStartService.setText(R.string.start_analysis);
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                checkFloatWindowPermission();
                return;
            }
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        });

        // 检查悬浮窗权限
        updatePermissionBanner();
        updateServiceButtonState();

        // 注册广播接收器
        IntentFilter filter = new IntentFilter("com.example.CHESS_RESULT");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionBanner();
        updateServiceButtonState();
    }

    private void updateServiceButtonState() {
        if (isServiceRunning(AnalysisService.class)) {
            btnStartService.setText(R.string.stop_analysis);
        } else {
            btnStartService.setText(R.string.start_analysis);
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void updatePermissionBanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                permissionLayout.setVisibility(android.view.View.GONE);
            } else {
                permissionLayout.setVisibility(android.view.View.VISIBLE);
            }
        }
    }

    private void initDepthSpinner() {
        java.util.List<Integer> depths = new java.util.ArrayList<>();
        for (int i = 6; i <= 30; i += 2) {
            depths.add(i);
        }
        android.widget.ArrayAdapter<Integer> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, depths);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDepth.setAdapter(adapter);

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 0.0.23 曾与桌面端对齐为 20；0.0.36 起移动端按手机硬件取值（movetime 2s）：
        // 20 层在时限内几乎不可达、只会每步吃满时限，故一次性迁移为 16
        //（此后用户手动选择的值正常保存，迁移不会再次触发）
        if (prefs.getInt("depth_migrated", 0) < 36) {
            prefs.edit().putInt("depth_migrated", 36).putInt(KEY_DEPTH, 16).apply();
        }
        int savedDepth = prefs.getInt(KEY_DEPTH, 16);
        int selection = depths.indexOf(savedDepth);
        if (selection >= 0) {
            spinnerDepth.setSelection(selection);
        }

        spinnerDepth.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                int depth = (int) parent.getItemAtPosition(position);
                prefs.edit().putInt(KEY_DEPTH, depth).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    /** 提示强度档位：立即持久化；启动分析时随 Intent 传给服务 */
    private void initHintSpinner() {
        spinnerHint = (android.widget.Spinner) findViewById(R.id.spinnerHint);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, HINT_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerHint.setAdapter(adapter);

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int saved = prefs.getInt(KEY_HINT, 0);
        if (saved >= 0 && saved < HINT_LABELS.length) {
            spinnerHint.setSelection(saved);
        }
        spinnerHint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                prefs.edit().putInt(KEY_HINT, position).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    /** 棋盘镜像悬浮窗开关：立即生效并持久化，服务下次启动也按此开关显示 */
    private void initBoardMirrorToggle() {
        CheckBox boardToggle = findViewById(R.id.checkboxBoardMirror);
        android.content.SharedPreferences ui = getSharedPreferences(FloatWindowManager.PREFS_UI, MODE_PRIVATE);
        boardToggle.setChecked(ui.getBoolean(FloatWindowManager.KEY_BOARD_MIRROR, true));
        boardToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                boardToggle.setChecked(false);
                Toast.makeText(this, R.string.grant_float_permission_tip, Toast.LENGTH_LONG).show();
                return;
            }
            ui.edit().putBoolean(FloatWindowManager.KEY_BOARD_MIRROR, isChecked).apply();
            if (isChecked) {
                FloatWindowManager.getInstance(this).showBoard();
            } else {
                FloatWindowManager.getInstance(this).hideBoard();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            Intent serviceIntent = new Intent(this, AnalysisService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            serviceIntent.putExtra("depth", (int) spinnerDepth.getSelectedItem());
            serviceIntent.putExtra("hintLevel", spinnerHint.getSelectedItemPosition());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            btnStartService.setText(R.string.stop_analysis);
            Toast.makeText(this, R.string.analysis_service_started, Toast.LENGTH_SHORT).show();
            moveTaskToBack(true); // 返回桌面，方便用户打开棋类APP
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(resultReceiver);
        } catch (Exception e) {
            // ignore
        }
    }

    private void checkFloatWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.grant_float_permission_tip, Toast.LENGTH_LONG).show();
                updatePermissionBanner();
            }
        }
    }
}