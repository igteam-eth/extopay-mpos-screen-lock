package com.extolab.extopay_lockscreen;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.cloudpos.DeviceException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demo screen for the CloudPOS SDK lock-screen APIs.
 * All SDK calls run on a background thread; results are written to the log view.
 */
public class MainActivity extends Activity {

    private LockScreenManager lockManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RadioGroup rgLockType;
    private EditText etCredential;
    private TextView tvHint;
    private EditText etLockText;
    private Spinner spNotification;
    private TextView tvLog;
    private ScrollView svLog;

    private static final String[] NOTIFICATION_LABELS = {
            "1 - Show all notifications",
            "2 - Hide all notifications",
            "3 - Non-sensitive only (default)"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        lockManager = new LockScreenManager(this);

        rgLockType = findViewById(R.id.rgLockType);
        etCredential = findViewById(R.id.etCredential);
        tvHint = findViewById(R.id.tvHint);
        etLockText = findViewById(R.id.etLockText);
        spNotification = findViewById(R.id.spNotification);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, NOTIFICATION_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spNotification.setAdapter(adapter);
        spNotification.setSelection(2);

        rgLockType.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateCredentialHint();
            }
        });
        updateCredentialHint();

        findViewById(R.id.btnEnable).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableLock();
            }
        });
        findViewById(R.id.btnDisable).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disableLock();
            }
        });
        findViewById(R.id.btnSetText).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLockText();
            }
        });
        findViewById(R.id.btnGetText).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getLockText();
            }
        });
        findViewById(R.id.btnSetNotification).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setNotification();
            }
        });
        findViewById(R.id.btnGetNotification).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getNotification();
            }
        });
        ((Button) findViewById(R.id.btnClearLog)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvLog.setText("");
            }
        });

        log("Ready. Device: " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void enableLock() {
        final LockScreenManager.LockType type = selectedType();
        final String value = etCredential.getText().toString().trim();
        try {
            LockScreenManager.validate(type, value);
        } catch (IllegalArgumentException e) {
            log("Invalid input: " + e.getMessage());
            return;
        }
        runSdk("Enable " + type + " lock", new SdkCall() {
            @Override
            public String call() throws DeviceException {
                lockManager.enableLock(type, value);
                return type + " lock set. Press the power key to test the lock screen.";
            }
        });
    }

    private void disableLock() {
        final LockScreenManager.LockType type = selectedType();
        final String value = etCredential.getText().toString().trim();
        runSdk("Disable " + type + " lock", new SdkCall() {
            @Override
            public String call() throws DeviceException {
                lockManager.disableLock(type, value);
                return "Screen lock removed.";
            }
        });
    }

    private void setLockText() {
        final String text = etLockText.getText().toString();
        runSdk("setTextOnLockScreen", new SdkCall() {
            @Override
            public String call() throws DeviceException {
                lockManager.setLockScreenText(text);
                return text.isEmpty() ? "Lock-screen text cleared." : "Lock-screen text set: \"" + text + "\"";
            }
        });
    }

    private void getLockText() {
        runSdk("getTextOnLockScreen", new SdkCall() {
            @Override
            public String call() throws DeviceException {
                String text = lockManager.getLockScreenText();
                return "Current lock-screen text: \"" + (text == null ? "" : text) + "\"";
            }
        });
    }

    private void setNotification() {
        final int flag = spNotification.getSelectedItemPosition() + 1; // 1, 2, 3
        runSdk("setLockScreenShowNotification(" + flag + ")", new SdkCall() {
            @Override
            public String call() throws DeviceException {
                lockManager.setLockScreenNotification(flag);
                return "Notification policy set to " + NOTIFICATION_LABELS[flag - 1];
            }
        });
    }

    private void getNotification() {
        runSdk("getLockScreenShowNotification", new SdkCall() {
            @Override
            public String call() throws DeviceException {
                final int flag = lockManager.getLockScreenNotification();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (flag >= 1 && flag <= 3) spNotification.setSelection(flag - 1);
                    }
                });
                return "Current notification policy: "
                        + (flag >= 1 && flag <= 3 ? NOTIFICATION_LABELS[flag - 1] : String.valueOf(flag));
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private LockScreenManager.LockType selectedType() {
        int id = rgLockType.getCheckedRadioButtonId();
        if (id == R.id.rbPassword) return LockScreenManager.LockType.PASSWORD;
        if (id == R.id.rbPattern) return LockScreenManager.LockType.PATTERN;
        return LockScreenManager.LockType.PIN;
    }

    private void updateCredentialHint() {
        switch (selectedType()) {
            case PIN:
                etCredential.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                etCredential.setHint("PIN, e.g. 1234");
                tvHint.setText("At least 4 digits, numbers only.");
                break;
            case PASSWORD:
                etCredential.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                etCredential.setHint("Password, e.g. abcd1234");
                tvHint.setText("At least 4 characters, letters and numbers.");
                break;
            case PATTERN:
                etCredential.setInputType(InputType.TYPE_CLASS_NUMBER);
                etCredential.setHint("Pattern, e.g. 14789");
                tvHint.setText("4-16 points on the grid, no repeats:\n  1  2  3\n  4  5  6\n  7  8  9\n14789 = L shape");
                break;
        }
    }

    private interface SdkCall {
        String call() throws DeviceException;
    }

    private void runSdk(final String name, final SdkCall call) {
        log("→ " + name + " ...");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = "✓ " + call.call();
                } catch (DeviceException e) {
                    result = "✗ " + name + " failed: DeviceException code=" + e.getCode()
                            + " " + e.getMessage()
                            + "\n  (Check the CREATE_USERS permission and that the APK is signed for this terminal.)";
                } catch (SecurityException e) {
                    result = "✗ " + name + " failed: SecurityException " + e.getMessage();
                } catch (Throwable t) {
                    result = "✗ " + name + " failed: " + t.getClass().getSimpleName() + " " + t.getMessage();
                }
                final String msg = result;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        log(msg);
                    }
                });
            }
        });
    }

    private void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        tvLog.append(time + "  " + msg + "\n");
        svLog.post(new Runnable() {
            @Override
            public void run() {
                svLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
