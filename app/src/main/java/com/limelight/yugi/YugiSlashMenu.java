package com.limelight.yugi;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.List;
import java.util.Locale;

/**
 * Slash-command completion, iOS style: typing "/" in a composer opens a rounded list above it with each command
 * and a short Hebrew line about it, filtered as you type. A tap fills the command in (or runs it, for the ones
 * without an argument). Comes and goes on a spring.
 */
class YugiSlashMenu extends LinearLayout {
    static final class Command {
        final String name, desc;
        final boolean takesArg;
        Command(String name, String desc, boolean takesArg) { this.name = name; this.desc = desc; this.takesArg = takesArg; }
    }

    interface OnPick { void onPick(Command c); }

    private final List<Command> commands;
    private final OnPick onPick;
    private boolean shown;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    YugiSlashMenu(Context c, List<Command> commands, OnPick onPick) {
        super(c);
        this.commands = commands;
        this.onPick = onPick;
        setOrientation(VERTICAL);
        setLayoutDirection(LAYOUT_DIRECTION_RTL);
        setBackground(YugiTheme.roundedStroke(c, 0xFF1C2230, 18, 0x24FFFFFF));
        setElevation(dp(10));
        setClipToOutline(true);
        setPadding(0, dp(6), 0, dp(6));
        setVisibility(GONE);
        setTag("slash");
        YugiSpring.of(this, DynamicAnimation.ALPHA).addEndListener((a, canceled, v, vel) -> {
            if (!canceled && !shown) setVisibility(GONE);
        });
    }

    boolean isShownNow() { return shown; }

    /** Follows the composer's text: open with the matching commands while it is a bare "/word". */
    void update(CharSequence text) {
        String t = text == null ? "" : text.toString();
        boolean want = t.startsWith("/") && !t.contains(" ") && !t.contains("\n");
        int n = 0;
        if (want) {
            String q = t.substring(1).toLowerCase(Locale.ROOT);
            removeAllViews();
            for (Command c : commands) {
                if (!c.name.startsWith(q)) continue;
                addView(row(c), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                n++;
            }
        }
        setShown(want && n > 0);
    }

    void setShown(boolean s) {
        if (s == shown) return;
        shown = s;
        if (s) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setAlpha(0f);
                setTranslationY(dp(10));
            }
            YugiSpring.to(this, DynamicAnimation.ALPHA, 1f);
            YugiSpring.to(this, DynamicAnimation.TRANSLATION_Y, 0f);
        } else {
            YugiSpring.to(this, DynamicAnimation.ALPHA, 0f);
            YugiSpring.to(this, DynamicAnimation.TRANSLATION_Y, dp(6));
        }
    }

    private View row(Command cmd) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(16), dp(6), dp(16), dp(6));
        r.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(YugiTheme.RIPPLE), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        r.setClickable(true);
        TextView name = YugiTheme.text(c, "⁦/" + cmd.name + "⁩", 15.5f, 700, YugiTheme.LINK);
        name.setMinWidth(dp(96));
        name.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        r.addView(name);
        TextView desc = YugiTheme.text(c, cmd.desc, 14, 400, YugiTheme.TEXT_DIM);
        desc.setMaxLines(2);
        desc.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        desc.setPaddingRelative(dp(10), 0, 0, 0);
        r.addView(desc, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.setContentDescription("/" + cmd.name + ", " + cmd.desc);
        r.setOnClickListener(v -> onPick.onPick(cmd));
        return r;
    }
}
