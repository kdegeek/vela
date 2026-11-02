package android.view;

/** Getter-only JVM carrier. Constants are checked against the installed SDK by run.py. */
public final class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1;
    public static final int FLAG_CANCELED = 32, FLAG_LONG_PRESS = 128,
            FLAG_CANCELED_LONG_PRESS = 256;
    public static final int KEYCODE_DPAD_CENTER = 23, KEYCODE_ENTER = 66,
            KEYCODE_NUMPAD_ENTER = 160, KEYCODE_SPACE = 62, KEYCODE_SHIFT_LEFT = 59,
            KEYCODE_BUTTON_A = 96;
    private final int action, code, repeat, flags;

    public KeyEvent(int action, int code, int repeat, int flags) {
        this.action = action;
        this.code = code;
        this.repeat = repeat;
        this.flags = flags;
    }
    public int getAction() { return action; }
    public int getKeyCode() { return code; }
    public int getRepeatCount() { return repeat; }
    public int getFlags() { return flags; }
    public boolean isCanceled() { return (flags & FLAG_CANCELED) != 0; }
}
