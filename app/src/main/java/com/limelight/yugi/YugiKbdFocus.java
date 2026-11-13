package com.limelight.yugi;

import java.lang.ref.WeakReference;

/**
 * One YugiPC keyboard on screen at a time: the desktop keyboard and the in-app field keyboard (chat)
 * hand the screen to each other. Whoever shows last owns it; the previous owner hides. Only a
 * keyboard that is really up counts (a closed screen never blocks the next one).
 */
final class YugiKbdFocus {
    interface Owner {
        void yieldKeyboard();
        boolean isShown();
        android.app.Activity ownerActivity();
    }

    private static WeakReference<Owner> current = new WeakReference<>(null);

    private YugiKbdFocus() {}

    static void claim(Owner o) {
        Owner prev = current.get();
        current = new WeakReference<>(o);
        if (prev != null && prev != o && prev.isShown() && prev.ownerActivity() == o.ownerActivity()) prev.yieldKeyboard();
    }

    static void release(Owner o) { if (current.get() == o) current = new WeakReference<>(null); }

    /** Another keyboard is up (the desktop keyboard should not pop up over the chat's). */
    static boolean heldByOther(Owner o) {
        Owner c = current.get();
        // only a keyboard up on the same screen counts (another activity is not visible)
        return c != null && c != o && c.isShown() && c.ownerActivity() == o.ownerActivity();
    }
}
