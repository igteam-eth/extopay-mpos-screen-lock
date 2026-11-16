package com.extolab.extopay_lockscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.cloudpos.DeviceException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exto "Security" settings page:
 * <ul>
 *   <li>Status card (None / PIN is on / Set outside this app)</li>
 *   <li>PIN: Set up PIN, Change PIN</li>
 *   <li>Authenticate: verify the screen lock with the system prompt (as a payment would)</li>
 *   <li>Lock screen: message and notification policy</li>
 *   <li>Remove screen lock: confirm the current lock, then turn it off through the SDK</li>
 * </ul>
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PIN = 1;

    private static final String[] NOTIFICATION_OPTIONS = {
            "Show all notifications",
            "Hide all notifications",
            "Hide sensitive content"
    };

    private PinStore store;
    private LockScreenManager lockManager;
    private DeviceAuth deviceAuth;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView ivStatus;
    private TextView tvStatus, tvStatusDetail, tvMessage, tvNotifications;
    private TextView tvAuthSummary, tvAuthResult;
    private View rowSetup, rowChange, rowAuth, ivAuthChevron;
    private View headerRemove, cardRemove, tvRemoveNote;

    private String lockScreenMessage = "";
    private int notificationFlag = LockScreenManager.NOTIFICATION_SENSITIVE_ONLY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        store = new PinStore(this);
        lockManager = new LockScreenManager(this);
        deviceAuth = new DeviceAuth(this);

        ivStatus = findViewById(R.id.ivStatus);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusDetail = findViewById(R.id.tvStatusDetail);
        tvMessage = findViewById(R.id.tvMessage);
        tvNotifications = findViewById(R.id.tvNotifications);
        rowSetup = findViewById(R.id.rowSetup);
        rowChange = findViewById(R.id.rowChange);
        rowAuth = findViewById(R.id.rowAuth);
        ivAuthChevron = findViewById(R.id.ivAuthChevron);
        tvAuthSummary = findViewById(R.id.tvAuthSummary);
        tvAuthResult = findViewById(R.id.tvAuthResult);
        headerRemove = findViewById(R.id.headerRemove);
        cardRemove = findViewById(R.id.cardRemove);
        tvRemoveNote = findViewById(R.id.tvRemoveNote);

        rowSetup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(PinActivity.intent(SettingsActivity.this, PinActivity.Mode.SETUP), REQ_PIN);
            }
        });
        rowChange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(PinActivity.intent(SettingsActivity.this, PinActivity.Mode.CHANGE), REQ_PIN);
            }
        });
        rowAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAuthentication();
            }
        });
        findViewById(R.id.rowRemove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeScreenLock();
            }
        });
        findViewById(R.id.rowMessage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editMessage();
            }
        });
        findViewById(R.id.rowNotifications).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNotifications();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        loadLockScreenInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (deviceAuth.onActivityResult(requestCode, resultCode)) return;
        if (requestCode == REQ_PIN && resultCode == RESULT_OK && data != null) {
            String msg = data.getStringExtra(PinActivity.EXTRA_RESULT_MESSAGE);
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Status card + PIN rows
    // ------------------------------------------------------------------

    private void renderStatus() {
        boolean appPin = store.hasPin();
        boolean secure = store.isDeviceSecure();

        // PIN was removed in Android Settings (or never really applied): forget it.
        if (appPin && !secure) {
            store.clearPin();
            appPin = false;
        }

        if (appPin) {
            setPill("PIN is on", R.color.success, R.color.success_soft);
            tvStatusDetail.setText("The terminal asks for the PIN when it wakes up.");
            ivStatus.setImageResource(R.drawable.ic_lock);
        } else if (secure) {
            setPill("Set outside this app", R.color.warning, R.color.warning_soft);
            tvStatusDetail.setText("A screen lock was set in Android Settings or by another app. "
                    + "You can change or remove it here, but the app can't check the current PIN.");
            ivStatus.setImageResource(R.drawable.ic_lock);
        } else {
            setPill("None", R.color.text_secondary, R.color.key_bg);
            tvStatusDetail.setText("Anyone can unlock this terminal.");
            ivStatus.setImageResource(R.drawable.ic_lock_open);
        }

        boolean locked = appPin || secure;
        rowSetup.setVisibility(locked ? View.GONE : View.VISIBLE);
        rowChange.setVisibility(locked ? View.VISIBLE : View.GONE);

        // Authenticate needs a screen lock to check against.
        rowAuth.setEnabled(secure);
        rowAuth.setAlpha(secure ? 1f : 0.5f);
        ivAuthChevron.setVisibility(secure ? View.VISIBLE : View.GONE);
        tvAuthSummary.setText(secure
                ? "Confirm it's you with the terminal's screen lock, the same check used before a payment"
                : "Set up a PIN first. Payments that need a screen-lock check won't work without one.");
        if (!secure) tvAuthResult.setVisibility(View.GONE);

        // Remove section only when there is something to remove.
        int removeVis = secure ? View.VISIBLE : View.GONE;
        headerRemove.setVisibility(removeVis);
        cardRemove.setVisibility(removeVis);
        tvRemoveNote.setVisibility(removeVis);
    }

    // ------------------------------------------------------------------
    // Authenticate
    // ------------------------------------------------------------------

    private void testAuthentication() {
        deviceAuth.authenticate("Verify it's you", "Enter the terminal's screen lock",
                new DeviceAuth.Callback() {
                    @Override
                    public void onAuthResult(boolean success, String message) {
                        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
                        showAuthResult(success, success
                                ? "✓ Verified at " + time
                                : "✗ Not verified at " + time + " · " + message);
                    }
                });
    }

    private void showAuthResult(boolean success, String text) {
        tvAuthResult.setVisibility(View.VISIBLE);
        tvAuthResult.setText(text);
        tvAuthResult.setTextColor(getResources().getColor(success ? R.color.success : R.color.danger));
        tvAuthResult.setBackground(pill(success ? R.color.success_soft : R.color.danger_soft));
    }

    // ------------------------------------------------------------------
    // Remove screen lock
    // ------------------------------------------------------------------

    private void removeScreenLock() {
        if (!store.isDeviceSecure()) {
            Toast.makeText(this, "No screen lock to remove", Toast.LENGTH_SHORT).show();
            renderStatus();
            return;
        }
        if (store.hasPin()) {
            // PIN set by this app: enter it on our PIN pad (checked against the saved
            // hash), confirm, then setPinLock(false, pin).
            startActivityForResult(PinActivity.intent(this, PinActivity.Mode.REMOVE), REQ_PIN);
            return;
        }
        // Lock set outside this app (could be PIN, password or pattern):
        // confirm with the system prompt first.
        deviceAuth.authenticate("Remove screen lock", "Confirm your current screen lock to remove it",
                new DeviceAuth.Callback() {
                    @Override
                    public void onAuthResult(boolean success, String message) {
                        if (success) {
                            confirmRemoveAnyLock();
                        } else {
                            Toast.makeText(SettingsActivity.this, "Not removed: " + message,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void confirmRemoveAnyLock() {
        new AlertDialog.Builder(this)
                .setTitle("Remove screen lock?")
                .setMessage("Anyone who picks up this terminal will be able to unlock it.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        removeAnyLock();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The lock type isn't known (and the app doesn't have the credential), so try each
     * SDK disable call until KeyguardManager reports the device is no longer secure.
     */
    private void removeAnyLock() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                LockScreenManager.LockType[] types = {
                        LockScreenManager.LockType.PIN,
                        LockScreenManager.LockType.PASSWORD,
                        LockScreenManager.LockType.PATTERN
                };
                String lastError = null;
                for (LockScreenManager.LockType type : types) {
                    try {
                        lockManager.disableLock(type, "");
                    } catch (DeviceException e) {
                        lastError = "code " + e.getCode();
                    } catch (Throwable t) {
                        lastError = t.getMessage();
                    }
                    if (!store.isDeviceSecure()) break;
                }
                final boolean removed = !store.isDeviceSecure();
                final String err = lastError;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (removed) {
                            store.clearPin();
                            Toast.makeText(SettingsActivity.this, "Screen lock removed", Toast.LENGTH_SHORT).show();
                        } else {
                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("Couldn't remove screen lock")
                                    .setMessage("The terminal kept its screen lock"
                                            + (err != null ? " (" + err + ")" : "") + ". "
                                            + "Remove it in Android Settings → Security, or ask WizarPOS "
                                            + "which value the SDK needs to turn it off.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                        renderStatus();
                    }
                });
            }
        });
    }

    private void setPill(String text, int textColor, int bgColor) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getResources().getColor(textColor));
        tvStatus.setBackground(pill(bgColor));
    }

    private GradientDrawable pill(int bgColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * getResources().getDisplayMetrics().density);
        bg.setColor(getResources().getColor(bgColor));
        return bg;
    }

    // ------------------------------------------------------------------
    // Lock-screen message + notifications
    // ------------------------------------------------------------------

    private void loadLockScreenInfo() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String text = null;
                Integer flag = null;
                try {
                    text = lockManager.getLockScreenText();
                } catch (Throwable ignored) {
                }
                try {
                    flag = lockManager.getLockScreenNotification();
                } catch (Throwable ignored) {
                }
                final String t = text;
                final Integer f = flag;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (t != null) lockScreenMessage = t;
                        tvMessage.setText(t == null ? "Not available on this terminal"
                                : t.isEmpty() ? "None" : t);
                        if (f != null) notificationFlag = f;
                        tvNotifications.setText(f == null ? "Not available on this terminal"
                                : labelFor(f));
                    }
                });
            }
        });
    }

    private void editMessage() {
        final EditText et = new EditText(this);
        et.setText(lockScreenMessage);
        et.setSelection(et.getText().length());
        et.setHint("e.g. ExtoPay · Terminal 001");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        et.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("Lock screen message")
                .setMessage("Shown on the lock screen, for example the shop name or a support number.")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveMessage(et.getText().toString().trim());
                    }
                })
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveMessage("");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveMessage(final String text) {
        runSdk(new SdkCall() {
            @Override
            public void call() throws DeviceException {
                lockManager.setLockScreenText(text);
            }
        }, text.isEmpty() ? "Message cleared" : "Message saved");
    }

    private void chooseNotifications() {
        new AlertDialog.Builder(this)
                .setTitle("Notifications on lock screen")
                .setSingleChoiceItems(NOTIFICATION_OPTIONS, notificationFlag - 1,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                final int flag = which + 1;
                                runSdk(new SdkCall() {
                                    @Override
                                    public void call() throws DeviceException {
                                        lockManager.setLockScreenNotification(flag);
                                    }
                                }, "Saved");
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String labelFor(int flag) {
        return flag >= 1 && flag <= 3 ? NOTIFICATION_OPTIONS[flag - 1] : "Unknown (" + flag + ")";
    }

    private interface SdkCall {
        void call() throws DeviceException;
    }

    private void runSdk(final SdkCall call, final String okMessage) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    call.call();
                } catch (DeviceException e) {
                    err = "Failed (code " + e.getCode() + ")";
                } catch (Throwable t) {
                    err = "Failed: " + t.getMessage();
                }
                final String msg = err == null ? okMessage : err;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
                        loadLockScreenInfo();
                    }
                });
            }
        });
    }
}
