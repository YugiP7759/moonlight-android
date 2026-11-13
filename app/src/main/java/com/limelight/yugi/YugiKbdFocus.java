package com.limelight.yugi;

/**
 * One YugiPC keyboard on screen at a time: the desktop keyboard and the in-app field keyboard (chat)
 * hand the screen to each other. Whoever shows last owns it; the previous owner hides.
 */
final class YugiKbdFocus {
    interface Owner { void yieldKeyboard(); }

    private static Owner current;

    private YugiKbdFocus() {}

    static void claim(Owner o) {
        Owner prev = current;
        current = o;
        if (prev != null && prev != o) prev.yieldKeyboard();
    }

    static void release(Owner o) { if (current == o) current = null; }

    /** Another keyboard is up (the desktop keyboard should not pop up over the chat's). */
    static boolean heldByOther(Owner o) { return current != null && current != o; }
}
