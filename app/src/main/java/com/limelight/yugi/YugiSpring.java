package com.limelight.yugi;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One interruptible spring per (view, property): a new target continues from the current position and
 * velocity, so panels never jump in or out (the tablet feel: nothing appears all at once).
 */
final class YugiSpring {
    private static final WeakHashMap<View, Map<DynamicAnimation.ViewProperty, SpringAnimation>> all = new WeakHashMap<>();

    private YugiSpring() {}

    static SpringAnimation of(View v, DynamicAnimation.ViewProperty p) {
        Map<DynamicAnimation.ViewProperty, SpringAnimation> m = all.get(v);
        if (m == null) { m = new HashMap<>(); all.put(v, m); }
        SpringAnimation a = m.get(p);
        if (a == null) {
            a = new SpringAnimation(v, p);
            boolean alpha = p == DynamicAnimation.ALPHA || p == DynamicAnimation.SCALE_X || p == DynamicAnimation.SCALE_Y;
            a.setSpring(new SpringForce().setDampingRatio(alpha ? 1f : 0.86f).setStiffness(alpha ? 900f : 520f));
            if (alpha) a.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA);
            m.put(p, a);
        }
        return a;
    }

    static void to(View v, DynamicAnimation.ViewProperty p, float value) {
        of(v, p).animateToFinalPosition(value);
    }
}
