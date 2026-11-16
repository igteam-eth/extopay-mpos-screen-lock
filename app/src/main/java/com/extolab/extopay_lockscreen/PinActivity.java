package com.extolab.extopay_lockscreen;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cloudpos.DeviceException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen PIN pad used for:
 * <ul>
 *   <li>SETUP:  Enter new PIN → Re-enter PIN → setPinLock(true, pin)</li>
 *   <li>CHANGE: Enter current PIN → Enter new PIN → Re-enter PIN → setPinLock(true, newPin)</li>
 *   <li>REMOVE: Enter current PIN → confirm dialog → setPinLock(false, currentPin)</li>
 * </ul>
 */
public class PinActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RESULT_MESSAGE = "result_message";

    public enum Mode { SETUP, CHANGE, REMOVE }

    private enum Step { CURRENT, NEW, CONFIRM }

    public static Intent intent(Context context, Mode mode) {
        return new Intent(context, PinActivity.class).putExtra(EXTRA_MODE, mode.name());
    }

    private Mode mode;
    private final List<Step> steps = new ArrayList<>();
    private Step step;

    private final StringBuilder input = new StringBuilder();
    private String currentPin;
    private String newPin;

    private PinStore store;
    private LockScreenManager lockManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvToolbarTitle, tvStep, tvTitle, tvSubtitle, tvError, tvBusy;
    private ImageView ivIcon;
    private LinearLayout dots, stepBar, keypad;
    private View busy, kOk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No screenshots / screen recording of the PIN screen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_pin);

        store = new PinStore(this);
        lockManager = new LockScreenManager(this);

        String m = getIntent().getStringExtra(EXTRA_MODE);
        mode = m == null ? Mode.SETUP : Mode.valueOf(m);
        switch (mode) {
            case SETUP:
                steps.add(Step.NEW);
                steps.add(Step.CONFIRM);
                break;
            case CHANGE:
                steps.add(Step.CURRENT);
                steps.add(Step.NEW);
                steps.add(Step.CONFIRM);
                break;
            case REMOVE:
                steps.add(Step.CURRENT);
                break;
        }

        tvToolbarTitle = findViewById(R.id.tvToolbarTitle);
        tvStep = findViewById(R.id.tvStep);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvError = findViewById(R.id.tvError);
        tvBusy = findViewById(R.id.tvBusy);
        ivIcon = findViewById(R.id.ivIcon);
        dots = findViewById(R.id.dots);
        stepBar = findViewById(R.id.stepBar);
        keypad = findViewById(R.id.keypad);
        busy = findViewById(R.id.busy);
        kOk = findViewById(R.id.kOk);

        tvToolbarTitle.setText(mode == Mode.SETUP ? "Set up PIN"
                : mode == Mode.CHANGE ? "Change PIN" : "Remove screen lock");

        int[] digitIds = {R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4,
                R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9};
        for (int i = 0; i < digitIds.length; i++) {
            final char digit = (char) ('0' + i);
            findViewById(digitIds[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    onDigit(digit);
                }
            });
        }
        findViewById(R.id.kDel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onDelete();
            }
        });
        findViewById(R.id.kDel).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                input.setLength(0);
                renderDots(false);
                return true;
            }
        });
        kOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        goTo(steps.get(0), null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == Step.CURRENT) startLockoutCountdownIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        wipe();
    }

    @Override
    public void onBackPressed() {
        if (busy.getVisibility() == View.VISIBLE) return;
        int i = steps.indexOf(step);
        if (i > 0) {
            goTo(steps.get(i - 1), null);
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    /** Physical keypad support (many POS terminals have one). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (busy.getVisibility() != View.VISIBLE) {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                onDigit((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                onDigit((char) ('0' + keyCode - KeyEvent.KEYCODE_NUMPAD_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                onDelete();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                submit();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private void goTo(Step next, String error) {
        step = next;
        input.setLength(0);
        if (next != Step.CONFIRM) newPin = next == Step.NEW ? null : newPin;
        if (next == Step.CURRENT) currentPin = null;

        switch (next) {
            case CURRENT:
                ivIcon.setImageResource(R.drawable.ic_lock);
                tvTitle.setText("Enter current PIN");
                tvSubtitle.setText(store.hasPin()
                        ? "Enter the PIN you use to unlock this terminal"
                        : "This PIN was set outside the app, so it can't be checked here.\nEnter the PIN set on the terminal.");
                break;
            case NEW:
                ivIcon.setImageResource(R.drawable.ic_pin);
                tvTitle.setText(mode == Mode.SETUP ? "Choose a PIN" : "Enter new PIN");
                tvSubtitle.setText("Use " + PinStore.MIN_LENGTH + "–" + PinStore.MAX_LENGTH + " digits");
                break;
            case CONFIRM:
                ivIcon.setImageResource(R.drawable.ic_shield);
                tvTitle.setText("Re-enter PIN");
                tvSubtitle.setText("Enter the same PIN again to confirm");
                break;
        }

        int index = steps.indexOf(next);
        tvStep.setText(steps.size() > 1 ? "Step " + (index + 1) + " of " + steps.size() : "");
        renderStepBar(index);
        showError(error);
        renderDots(false);
        if (next == Step.CURRENT) startLockoutCountdownIfNeeded();
    }

    private void onDigit(char d) {
        if (!keypadEnabled()) return;
        if (input.length() >= PinStore.MAX_LENGTH) return;
        input.append(d);
        if (tvError.getText().length() > 0) showError(null);
        renderDots(false);
        // Re-enter step: submit as soon as the length matches.
        if (step == Step.CONFIRM && newPin != null && input.length() == newPin.length()) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (step == Step.CONFIRM) submit();
                }
            }, 150);
        }
    }

    private void onDelete() {
        if (!keypadEnabled() || input.length() == 0) return;
        input.setLength(input.length() - 1);
        renderDots(false);
    }

    private void submit() {
        if (!keypadEnabled()) return;
        String value = input.toString();
        if (value.length() < PinStore.MIN_LENGTH) {
            fail("PIN must be at least " + PinStore.MIN_LENGTH + " digits");
            return;
        }

        switch (step) {
            case CURRENT:
                if (store.verify(value)) {
                    currentPin = value;
                    if (mode == Mode.REMOVE) {
                        confirmRemove();
                    } else {
                        goTo(Step.NEW, null);
                    }
                } else {
                    long lock = store.lockoutRemainingMs();
                    if (lock > 0) {
                        fail(null);
                        startLockoutCountdownIfNeeded();
                    } else {
                        int left = store.attemptsLeft();
                        fail("Wrong PIN. " + left + (left == 1 ? " attempt" : " attempts") + " left");
                    }
                }
                break;

            case NEW:
                if (mode == Mode.CHANGE && value.equals(currentPin)) {
                    fail("New PIN must be different from the current PIN");
                    return;
                }
                newPin = value;
                goTo(Step.CONFIRM, null);
                break;

            case CONFIRM:
                if (!value.equals(newPin)) {
                    shake();
                    goTo(Step.NEW, "PINs didn't match. Try again.");
                    return;
                }
                apply();
                break;
        }
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle("Remove screen lock?")
                .setMessage("Anyone who picks up this terminal will be able to unlock it.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        apply();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------------
    // SDK call
    // ------------------------------------------------------------------

    private void apply() {
        final String busyText = mode == Mode.SETUP ? "Setting PIN…"
                : mode == Mode.CHANGE ? "Changing PIN…" : "Removing screen lock…";
        showBusy(busyText);
        final String oldPin = currentPin;
        final String pin = newPin;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    switch (mode) {
                        case SETUP:
                            lockManager.enableLock(LockScreenManager.LockType.PIN, pin);
                            break;
                        case CHANGE:
                            try {
                                // Try to replace the PIN directly first.
                                lockManager.enableLock(LockScreenManager.LockType.PIN, pin);
                            } catch (DeviceException first) {
                                // Some firmware may need the old lock removed first.
                                lockManager.disableLock(LockScreenManager.LockType.PIN, oldPin);
                                lockManager.enableLock(LockScreenManager.LockType.PIN, pin);
                            }
                            break;
                        case REMOVE:
                            lockManager.disableLock(LockScreenManager.LockType.PIN, oldPin);
                            break;
                    }
                } catch (DeviceException e) {
                    error = "The terminal returned an error (code " + e.getCode() + ")."
                            + (e.getMessage() != null ? "\n" + e.getMessage() : "");
                } catch (SecurityException e) {
                    error = "Permission denied. The app needs android.permission.CREATE_USERS "
                            + "and must be signed for this terminal.";
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + ": " + t.getMessage();
                }

                final String err = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (err == null) onApplied();
                        else onApplyFailed(err);
                    }
                });
            }
        });
    }

    private void onApplied() {
        String message;
        if (mode == Mode.REMOVE) {
            store.clearPin();
            message = "Screen lock removed";
        } else {
            store.savePin(newPin);
            message = mode == Mode.SETUP ? "PIN set" : "PIN changed";
        }
        wipe();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_MESSAGE, message));
        finish();
    }

    private void onApplyFailed(String error) {
        hideBusy();
        new AlertDialog.Builder(this)
                .setTitle(mode == Mode.REMOVE ? "Couldn't remove screen lock"
                        : mode == Mode.CHANGE ? "Couldn't change PIN" : "Couldn't set PIN")
                .setMessage(error)
                .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        apply();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------------
    // Lockout after too many wrong PINs
    // ------------------------------------------------------------------

    private final Runnable lockoutTick = new Runnable() {
        @Override
        public void run() {
            long ms = store.lockoutRemainingMs();
            if (ms > 0 && step == Step.CURRENT) {
                setKeypadEnabled(false);
                showError("Too many attempts. Try again in " + ((ms + 999) / 1000) + "s");
                handler.postDelayed(this, 500);
            } else {
                setKeypadEnabled(true);
                if (step == Step.CURRENT) showError(null);
            }
        }
    };

    private void startLockoutCountdownIfNeeded() {
        handler.removeCallbacks(lockoutTick);
        if (store.lockoutRemainingMs() > 0) {
            handler.post(lockoutTick);
        } else {
            setKeypadEnabled(true);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void renderDots(boolean error) {
        dots.removeAllViews();
        int len = input.length();
        int slots = step == Step.CONFIRM && newPin != null
                ? newPin.length()
                : Math.max(PinStore.MIN_LENGTH, len);
        int size = dp(14);
        int gap = dp(slots > 8 ? 6 : 9);
        for (int i = 0; i < slots; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(gap, 0, gap, 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(error ? R.drawable.dot_error
                    : i < len ? R.drawable.dot_filled : R.drawable.dot_empty);
            dots.addView(dot);
        }
        kOk.setEnabled(keypadEnabled() && len >= PinStore.MIN_LENGTH);
        dots.setContentDescription(len + " digits entered");
    }

    private void renderStepBar(int current) {
        stepBar.removeAllViews();
        if (steps.size() < 2) return;
        for (int i = 0; i < steps.size(); i++) {
            View seg = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1f);
            lp.setMargins(i == 0 ? 0 : dp(3), 0, i == steps.size() - 1 ? 0 : dp(3), 0);
            seg.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(2));
            bg.setColor(getResources().getColor(i <= current ? R.color.primary : R.color.divider));
            seg.setBackground(bg);
            stepBar.addView(seg);
        }
    }

    private void fail(String message) {
        shake();
        input.setLength(0);
        renderDots(true);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderDots(false);
            }
        }, 450);
        if (message != null) showError(message);
    }

    private void shake() {
        dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        float d = dp(10);
        ObjectAnimator.ofFloat(dots, "translationX", 0, d, -d, d * 0.8f, -d * 0.8f, d * 0.4f, -d * 0.4f, 0)
                .setDuration(400)
                .start();
    }

    private void showError(String message) {
        tvError.setText(message == null ? "" : message);
    }

    private void showBusy(String text) {
        tvBusy.setText(text);
        busy.setVisibility(View.VISIBLE);
    }

    private void hideBusy() {
        busy.setVisibility(View.GONE);
    }

    private boolean keypadEnabled = true;

    private boolean keypadEnabled() {
        return keypadEnabled && busy.getVisibility() != View.VISIBLE;
    }

    private void setKeypadEnabled(boolean enabled) {
        keypadEnabled = enabled;
        keypad.setAlpha(enabled ? 1f : 0.35f);
        kOk.setEnabled(enabled && input.length() >= PinStore.MIN_LENGTH);
    }

    private void wipe() {
        input.setLength(0);
        currentPin = null;
        newPin = null;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
