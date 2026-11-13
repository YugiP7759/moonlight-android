package com.limelight.yugi;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen gamepad (W3C "standard" mapping) drawn over the games WebView.
 * Its state is read by the injected JS polyfill through {@link #jsState()}.
 * Fully editable: drag, resize and change opacity per control; layouts are saved per screen
 * (cover / inner, landscape / portrait).
 */
public class VirtualPadView extends View {

    // W3C standard gamepad button indices
    public static final int A = 0, B = 1, X = 2, Y = 3, LB = 4, RB = 5, LT = 6, RT = 7,
            BACK = 8, START = 9, LS = 10, RS = 11, UP = 12, DOWN = 13, LEFT = 14, RIGHT = 15, HOME = 16;
    private static final int BUTTON_COUNT = 17;

    static final int T_BUTTON = 0, T_STICK = 1, T_DPAD = 2;

    static final class Control {
        String id;
        int type;
        int button;      // for T_BUTTON
        int axisBase;    // for T_STICK: 0 (left) or 2 (right)
        String label;
        float cx, cy;    // center, fraction of the view
        float sizeDp;
        float alpha;
        // runtime
        int pointerId = -1;
        float knobX, knobY; // stick offset -1..1
        int dpadMask;        // bit per direction

        Control copy() {
            Control c = new Control();
            c.id = id; c.type = type; c.button = button; c.axisBase = axisBase; c.label = label;
            c.cx = cx; c.cy = cy; c.sizeDp = sizeDp; c.alpha = alpha;
            return c;
        }
    }

    public interface EditListener {
        void onEditModeChanged(boolean editing);
        void onSelectionChanged(Control selected);
    }

    private final List<Control> controls = new ArrayList<>();
    private final boolean[] buttons = new boolean[BUTTON_COUNT];
    private final float[] axes = new float[4];
    private volatile String state = "";
    private volatile boolean padEnabled = true;

    private boolean editing;
    private Control selected;
    private float dragDx, dragDy;
    private int dragPointer = -1;
    private EditListener editListener;
    private String layoutKey = "";

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Control> byPointer = new SparseArray<>();

    public VirtualPadView(Context ctx) {
        super(ctx);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        setDefaults(true);
        publish();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- layout

    private static Control c(String id, int type, int button, String label, float cx, float cy, float size) {
        Control c = new Control();
        c.id = id; c.type = type; c.button = button; c.label = label;
        c.cx = cx; c.cy = cy; c.sizeDp = size; c.alpha = 0.55f;
        if (type == T_STICK) c.axisBase = button;
        return c;
    }

    /** Default layout; landscape and portrait differ only in placement. */
    private void setDefaults(boolean landscape) {
        controls.clear();
        if (landscape) {
            controls.add(c("ls", T_STICK, 0, "", 0.14f, 0.52f, 130));
            controls.add(c("dpad", T_DPAD, 0, "", 0.28f, 0.80f, 120));
            controls.add(c("rs", T_STICK, 2, "", 0.70f, 0.80f, 110));
            controls.add(c("a", T_BUTTON, A, "A", 0.88f, 0.70f, 60));
            controls.add(c("b", T_BUTTON, B, "B", 0.95f, 0.56f, 60));
            controls.add(c("x", T_BUTTON, X, "X", 0.81f, 0.56f, 60));
            controls.add(c("y", T_BUTTON, Y, "Y", 0.88f, 0.42f, 60));
            controls.add(c("lb", T_BUTTON, LB, "LB", 0.08f, 0.24f, 56));
            controls.add(c("lt", T_BUTTON, LT, "LT", 0.08f, 0.10f, 56));
            controls.add(c("rb", T_BUTTON, RB, "RB", 0.92f, 0.24f, 56));
            controls.add(c("rt", T_BUTTON, RT, "RT", 0.92f, 0.10f, 56));
            controls.add(c("back", T_BUTTON, BACK, "⧉", 0.42f, 0.90f, 44));
            controls.add(c("home", T_BUTTON, HOME, "⌂", 0.50f, 0.90f, 44));
            controls.add(c("start", T_BUTTON, START, "☰", 0.58f, 0.90f, 44));
            controls.add(c("l3", T_BUTTON, LS, "L3", 0.04f, 0.72f, 44));
            controls.add(c("r3", T_BUTTON, RS, "R3", 0.60f, 0.72f, 44));
        } else {
            controls.add(c("ls", T_STICK, 0, "", 0.22f, 0.70f, 130));
            controls.add(c("dpad", T_DPAD, 0, "", 0.22f, 0.88f, 110));
            controls.add(c("rs", T_STICK, 2, "", 0.62f, 0.88f, 100));
            controls.add(c("a", T_BUTTON, A, "A", 0.80f, 0.76f, 60));
            controls.add(c("b", T_BUTTON, B, "B", 0.92f, 0.70f, 60));
            controls.add(c("x", T_BUTTON, X, "X", 0.68f, 0.70f, 60));
            controls.add(c("y", T_BUTTON, Y, "Y", 0.80f, 0.64f, 60));
            controls.add(c("lb", T_BUTTON, LB, "LB", 0.10f, 0.56f, 52));
            controls.add(c("lt", T_BUTTON, LT, "LT", 0.10f, 0.49f, 52));
            controls.add(c("rb", T_BUTTON, RB, "RB", 0.90f, 0.56f, 52));
            controls.add(c("rt", T_BUTTON, RT, "RT", 0.90f, 0.49f, 52));
            controls.add(c("back", T_BUTTON, BACK, "⧉", 0.40f, 0.96f, 42));
            controls.add(c("home", T_BUTTON, HOME, "⌂", 0.50f, 0.96f, 42));
            controls.add(c("start", T_BUTTON, START, "☰", 0.60f, 0.96f, 42));
            controls.add(c("l3", T_BUTTON, LS, "L3", 0.40f, 0.78f, 42));
            controls.add(c("r3", T_BUTTON, RS, "R3", 0.62f, 0.76f, 42));
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("yugipc_pad", Context.MODE_PRIVATE);
    }

    /** Loads the layout saved for this screen (e.g. "cover_l", "inner_p"), or the defaults. */
    public void loadLayout(String key, boolean landscape) {
        layoutKey = key;
        setDefaults(landscape);
        String json = prefs().getString("layout_" + key, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    for (Control c : controls) {
                        if (c.id.equals(o.getString("id"))) {
                            c.cx = (float) o.getDouble("x");
                            c.cy = (float) o.getDouble("y");
                            c.sizeDp = (float) o.getDouble("s");
                            c.alpha = (float) o.getDouble("a");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        releaseAll();
        invalidate();
    }

    public void saveLayout() {
        try {
            JSONArray arr = new JSONArray();
            for (Control c : controls) {
                arr.put(new JSONObject().put("id", c.id).put("x", c.cx).put("y", c.cy)
                        .put("s", c.sizeDp).put("a", c.alpha));
            }
            prefs().edit().putString("layout_" + layoutKey, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void resetLayout(boolean landscape) {
        prefs().edit().remove("layout_" + layoutKey).apply();
        setDefaults(landscape);
        selected = null;
        if (editListener != null) editListener.onSelectionChanged(null);
        invalidate();
    }

    // ---------------------------------------------------------------- edit mode

    public void setEditListener(EditListener l) { editListener = l; }
    public boolean isEditing() { return editing; }

    public void setEditing(boolean on) {
        editing = on;
        selected = null;
        releaseAll();
        if (editListener != null) {
            editListener.onEditModeChanged(on);
            editListener.onSelectionChanged(null);
        }
        invalidate();
    }

    public Control getSelected() { return selected; }

    public void setSelectedSize(float sizeDp) {
        if (selected != null) { selected.sizeDp = sizeDp; invalidate(); }
    }

    public void setSelectedAlpha(float alpha) {
        if (selected != null) { selected.alpha = alpha; invalidate(); }
    }

    // ---------------------------------------------------------------- state for JS

    public void setPadEnabled(boolean on) {
        padEnabled = on;
        releaseAll();
        setVisibility(on ? VISIBLE : GONE);
    }

    public boolean isPadEnabled() { return padEnabled; }

    /** "c;b0b1...b16;a0,a1,a2,a3" where c = 1 when the virtual pad is connected. */
    public String jsState() { return state; }

    private void publish() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(padEnabled ? '1' : '0').append(';');
        for (boolean b : buttons) sb.append(b ? '1' : '0');
        sb.append(';');
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(axes[i] * 1000) / 1000f);
        }
        state = sb.toString();
    }

    private void releaseAll() {
        for (int i = 0; i < BUTTON_COUNT; i++) buttons[i] = false;
        for (int i = 0; i < 4; i++) axes[i] = 0;
        for (Control c : controls) { c.pointerId = -1; c.knobX = c.knobY = 0; c.dpadMask = 0; }
        byPointer.clear();
        publish();
    }

    // ---------------------------------------------------------------- drawing

    private float radiusPx(Control c) { return dp(c.sizeDp) / 2f; }
    private float cxPx(Control c) { return c.cx * getWidth(); }
    private float cyPx(Control c) { return c.cy * getHeight(); }

    @Override
    protected void onDraw(Canvas canvas) {
        for (Control c : controls) {
            float x = cxPx(c), y = cyPx(c), r = radiusPx(c);
            int a = (int) (255 * c.alpha);
            boolean pressed = c.pointerId != -1;
            fill.setColor(Color.argb((int) (a * (pressed ? 0.75f : 0.45f)), 20, 24, 32));
            stroke.setColor(editing && c == selected ? Color.argb(255, 61, 123, 255)
                    : Color.argb(a, 255, 255, 255));
            if (c.type == T_STICK) {
                canvas.drawCircle(x, y, r, fill);
                canvas.drawCircle(x, y, r, stroke);
                fill.setColor(Color.argb(a, 230, 230, 235));
                canvas.drawCircle(x + c.knobX * r * 0.55f, y + c.knobY * r * 0.55f, r * 0.42f, fill);
            } else if (c.type == T_DPAD) {
                float arm = r * 0.36f;
                RectF h = new RectF(x - r, y - arm, x + r, y + arm);
                RectF v = new RectF(x - arm, y - r, x + arm, y + r);
                canvas.drawRoundRect(h, arm * 0.4f, arm * 0.4f, fill);
                canvas.drawRoundRect(v, arm * 0.4f, arm * 0.4f, fill);
                canvas.drawRoundRect(h, arm * 0.4f, arm * 0.4f, stroke);
                canvas.drawRoundRect(v, arm * 0.4f, arm * 0.4f, stroke);
                fill.setColor(Color.argb(a, 230, 230, 235));
                if ((c.dpadMask & 1) != 0) canvas.drawCircle(x, y - r * 0.65f, arm * 0.6f, fill);
                if ((c.dpadMask & 2) != 0) canvas.drawCircle(x, y + r * 0.65f, arm * 0.6f, fill);
                if ((c.dpadMask & 4) != 0) canvas.drawCircle(x - r * 0.65f, y, arm * 0.6f, fill);
                if ((c.dpadMask & 8) != 0) canvas.drawCircle(x + r * 0.65f, y, arm * 0.6f, fill);
            } else {
                canvas.drawCircle(x, y, r, fill);
                canvas.drawCircle(x, y, r, stroke);
                text.setTextSize(r * 0.7f);
                text.setAlpha(Math.min(255, a + 60));
                canvas.drawText(c.label, x, y + r * 0.25f, text);
            }
        }
    }

    // ---------------------------------------------------------------- touch

    private Control hit(float x, float y) {
        Control best = null;
        float bestD = Float.MAX_VALUE;
        for (Control c : controls) {
            float dx = x - cxPx(c), dy = y - cyPx(c);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float reach = radiusPx(c) * (c.type == T_BUTTON ? 1.15f : 1.05f);
            if (d <= reach && d < bestD) { best = c; bestD = d; }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return onEditTouch(e);

        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        boolean changed = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Control c = hit(e.getX(idx), e.getY(idx));
                if (c == null || c.pointerId != -1) {
                    // Not on a control: let the touch fall through to the page
                    return action == MotionEvent.ACTION_POINTER_DOWN;
                }
                c.pointerId = e.getPointerId(idx);
                byPointer.put(c.pointerId, c);
                applyPointer(c, e.getX(idx), e.getY(idx));
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                changed = true;
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Control c = byPointer.get(e.getPointerId(i));
                    if (c != null) { applyPointer(c, e.getX(i), e.getY(i)); changed = true; }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL) {
                    releaseAll();
                    invalidate();
                    return true;
                }
                Control c = byPointer.get(e.getPointerId(idx));
                if (c != null) { release(c); byPointer.remove(e.getPointerId(idx)); changed = true; }
                break;
            }
        }
        if (changed) { recompute(); invalidate(); }
        return byPointer.size() > 0 || changed;
    }

    private void applyPointer(Control c, float x, float y) {
        float r = radiusPx(c);
        float dx = (x - cxPx(c)) / r, dy = (y - cyPx(c)) / r;
        if (c.type == T_STICK) {
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) { dx /= len; dy /= len; }
            c.knobX = dx; c.knobY = dy;
        } else if (c.type == T_DPAD) {
            int m = 0;
            float dead = 0.25f;
            if (dy < -dead && Math.abs(dy) >= Math.abs(dx) * 0.4f) m |= 1;
            if (dy > dead && Math.abs(dy) >= Math.abs(dx) * 0.4f) m |= 2;
            if (dx < -dead && Math.abs(dx) >= Math.abs(dy) * 0.4f) m |= 4;
            if (dx > dead && Math.abs(dx) >= Math.abs(dy) * 0.4f) m |= 8;
            if (m != c.dpadMask) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            c.dpadMask = m;
        }
    }

    private void release(Control c) {
        c.pointerId = -1;
        c.knobX = c.knobY = 0;
        c.dpadMask = 0;
    }

    private void recompute() {
        for (int i = 0; i < BUTTON_COUNT; i++) buttons[i] = false;
        for (int i = 0; i < 4; i++) axes[i] = 0;
        for (Control c : controls) {
            if (c.pointerId == -1) continue;
            if (c.type == T_BUTTON) buttons[c.button] = true;
            else if (c.type == T_STICK) {
                float dead = 0.08f;
                axes[c.axisBase] = Math.abs(c.knobX) < dead ? 0 : c.knobX;
                axes[c.axisBase + 1] = Math.abs(c.knobY) < dead ? 0 : c.knobY;
            } else if (c.type == T_DPAD) {
                buttons[UP] |= (c.dpadMask & 1) != 0;
                buttons[DOWN] |= (c.dpadMask & 2) != 0;
                buttons[LEFT] |= (c.dpadMask & 4) != 0;
                buttons[RIGHT] |= (c.dpadMask & 8) != 0;
            }
        }
        publish();
    }

    private boolean onEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                Control c = hit(e.getX(), e.getY());
                selected = c;
                if (editListener != null) editListener.onSelectionChanged(c);
                if (c != null) {
                    dragPointer = e.getPointerId(0);
                    dragDx = cxPx(c) - e.getX();
                    dragDy = cyPx(c) - e.getY();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected != null && dragPointer != -1) {
                    int i = e.findPointerIndex(dragPointer);
                    if (i >= 0 && getWidth() > 0 && getHeight() > 0) {
                        selected.cx = clamp((e.getX(i) + dragDx) / getWidth());
                        selected.cy = clamp((e.getY(i) + dragDy) / getHeight());
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragPointer = -1;
                return true;
        }
        return true;
    }

    private static float clamp(float v) { return Math.max(0.02f, Math.min(0.98f, v)); }
}
