package com.extolab.extopay_lockscreen;

import android.content.Context;

import com.cloudpos.DeviceException;
import com.cloudpos.advance.ext.POSTerminalAdvance;
import com.cloudpos.advance.ext.system.ISystemDevice;

import java.util.HashSet;
import java.util.Set;

/**
 * Wrapper around the CloudPOS SDK (v1.8.3.13+) screen-lock APIs in
 * {@link ISystemDevice}:
 * <ul>
 *   <li>setPinLock(boolean enable, String pin)</li>
 *   <li>setPasswordLock(boolean enable, String password)</li>
 *   <li>setPatternLock(boolean enable, String pattern)</li>
 *   <li>setTextOnLockScreen / getTextOnLockScreen</li>
 *   <li>setLockScreenShowNotification / getLockScreenShowNotification</li>
 * </ul>
 *
 * Every method opens the system device, does its work and closes it again.
 * {@code ISystemDevice.open()} is synchronous, so never call these methods on the
 * main (UI) thread.
 */
public class LockScreenManager {

    /** Lock-screen notification visibility values from the SDK docs. */
    public static final int NOTIFICATION_ALL = 1;
    public static final int NOTIFICATION_NONE = 2;
    public static final int NOTIFICATION_SENSITIVE_ONLY = 3;

    public enum LockType { PIN, PASSWORD, PATTERN }

    private final Context context;

    public LockScreenManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // Screen lock
    // ------------------------------------------------------------------

    /**
     * Turns on a screen lock of the given type.
     *
     * @param credential PIN ("1234"), password ("abcd1234") or pattern ("14789").
     */
    public void enableLock(LockType type, String credential) throws DeviceException {
        validate(type, credential);
        setLock(type, true, credential);
    }

    /**
     * Turns the screen lock off (the device goes back to "None"/"Swipe").
     *
     * @param currentCredential the credential that is set now. The SDK docs do not say
     *                          whether this is checked when disabling, so pass the
     *                          current value when you know it; an empty string is
     *                          sent otherwise.
     */
    public void disableLock(LockType type, String currentCredential) throws DeviceException {
        setLock(type, false, currentCredential == null ? "" : currentCredential);
    }

    private void setLock(final LockType type, final boolean enable, final String value)
            throws DeviceException {
        withDevice(new DeviceAction<Void>() {
            @Override
            public Void run(ISystemDevice device) throws DeviceException {
                switch (type) {
                    case PIN:
                        device.setPinLock(enable, value);
                        break;
                    case PASSWORD:
                        device.setPasswordLock(enable, value);
                        break;
                    case PATTERN:
                        device.setPatternLock(enable, value);
                        break;
                }
                return null;
            }
        });
    }

    // ------------------------------------------------------------------
    // Lock-screen text and notification policy
    // ------------------------------------------------------------------

    /** Shows {@code text} on the lock screen; an empty string clears it. */
    public void setLockScreenText(final String text) throws DeviceException {
        withDevice(new DeviceAction<Void>() {
            @Override
            public Void run(ISystemDevice device) throws DeviceException {
                device.setTextOnLockScreen(text == null ? "" : text);
                return null;
            }
        });
    }

    public String getLockScreenText() throws DeviceException {
        return withDevice(new DeviceAction<String>() {
            @Override
            public String run(ISystemDevice device) throws DeviceException {
                return device.getTextOnLockScreen();
            }
        });
    }

    /** @param flag {@link #NOTIFICATION_ALL}, {@link #NOTIFICATION_NONE} or
     *              {@link #NOTIFICATION_SENSITIVE_ONLY}. */
    public void setLockScreenNotification(final int flag) throws DeviceException {
        withDevice(new DeviceAction<Void>() {
            @Override
            public Void run(ISystemDevice device) throws DeviceException {
                device.setLockScreenShowNotification(flag);
                return null;
            }
        });
    }

    public int getLockScreenNotification() throws DeviceException {
        return withDevice(new DeviceAction<Integer>() {
            @Override
            public Integer run(ISystemDevice device) throws DeviceException {
                return device.getLockScreenShowNotification();
            }
        });
    }

    // ------------------------------------------------------------------
    // Validation (rules taken from the SDK javadoc)
    // ------------------------------------------------------------------

    /** Throws IllegalArgumentException with a readable message when the credential is invalid. */
    public static void validate(LockType type, String value) {
        if (value == null) value = "";
        switch (type) {
            case PIN:
                // "must be at least 4 digits (numeric only)"
                if (!value.matches("\\d{4,}")) {
                    throw new IllegalArgumentException("PIN must be at least 4 digits, numbers only.");
                }
                break;
            case PASSWORD:
                // "must be at least 4 characters and may contain letters and numbers"
                if (!value.matches("[A-Za-z0-9]{4,}")) {
                    throw new IllegalArgumentException(
                            "Password must be at least 4 characters, letters and numbers only.");
                }
                break;
            case PATTERN:
                // "4 to 16 connected points in a 3x3 grid", digits 1..9, no repeats
                //   1 2 3
                //   4 5 6
                //   7 8 9
                if (!value.matches("[1-9]{4,16}")) {
                    throw new IllegalArgumentException(
                            "Pattern must be 4 or more points, using digits 1-9 (e.g. 14789 = L shape).");
                }
                Set<Character> seen = new HashSet<>();
                for (char c : value.toCharArray()) {
                    if (!seen.add(c)) {
                        throw new IllegalArgumentException(
                                "Pattern points cannot repeat (" + c + " is used twice).");
                    }
                }
                break;
        }
    }

    // ------------------------------------------------------------------
    // Open / close helper
    // ------------------------------------------------------------------

    private interface DeviceAction<T> {
        T run(ISystemDevice device) throws DeviceException;
    }

    private <T> T withDevice(DeviceAction<T> action) throws DeviceException {
        ISystemDevice device = POSTerminalAdvance.getInstance().getSystemDevice();
        if (device == null) {
            throw new IllegalStateException("ISystemDevice not available on this terminal.");
        }
        boolean openedHere = false;
        try {
            if (!device.isOpened()) {
                device.open(context);
                openedHere = true;
            }
            return action.run(device);
        } finally {
            if (openedHere) {
                try {
                    device.close();
                } catch (DeviceException ignored) {
                    // already closed
                }
            }
        }
    }
}
