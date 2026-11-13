package com.limelight.yugi;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen gamepad (W3C "standard" mapping) over the games WebView, tuned for mobile shooters:
 * - left half: floating stick, born where the thumb lands (radial deadzone, linear, no jumps);
 * - right half: "look" zone, drag speed becomes right-stick deflection (sensitivity, curve,
 *   anti-deadzone) and decays to zero within ~50 ms when the finger stops; optional gyro aim;
 * - buttons keep working anywhere, with full multi-touch.
 * State is read by the injected JS polyfill through {@link #jsState()} on every getGamepads() call.
 * Layouts are editable and saved per screen (cover / inner, landscape / portrait).
 */
public class VirtualPadView extends View implements SensorEventListener {

    // W3C standard gamepad button indices
    public static final int A = 0, B = 1, X = 2, Y = 3, LB = 4, RB = 5, LT = 6, RT = 7,
            BACK = 8, START = 9, LS = 10, RS = 11, UP = 12, DOWN = 13, LEFT = 14, RIGHT = 15, HOME = 16;
    private static final int BUTTON_COUNT = 17;
    private static final long TICK_NS = 8_000_000L;

    static final int T_BUTTON = 0, T_STICK = 1, T_DPAD = 2;

    static final class Control {
        String id;
        int type;
        int button;      // for T_BUTTON
        String label;
        float cx, cy;    // center: x fraction of width, y fraction of the area below the toolbar
        float sizeDp;
        float alpha;
        // runtime
        int pointerId = -1;
        int dpadMask;
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
    private PadSettings settings;

    // floating left stick
    private int leftPointer = -1;
    private float leftOx, leftOy, leftX, leftY;
    // look zone
    private int lookPointer = -1;
    private float lookLastX, lookLastY;
    private long lookLastT;
    private long lookLastMoveNs;
    private float lookX, lookY;
    // gyro
    private float gyroX, gyroY;
    private SensorManager sensors;
    private boolean ticking;

    private boolean editing;
    private Control selected;
    private float dragDx, dragDy;
    private int dragPointer = -1;
    private EditListener editListener;
    private String layoutKey = "";
    private int topInset;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Control> byPointer = new SparseArray<>();

    public VirtualPadView(Context ctx) {
        super(ctx);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(YugiTheme.font(ctx, 600));
        editPaint.setStyle(Paint.Style.STROKE);
        settings = PadSettings.load(ctx);
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
        c.cx = cx; c.cy = cy; c.sizeDp = size; c.alpha = 0.6f;
        return c;
    }

    /**
     * Default layout. The left stick ("ls") is only the resting hint of the floating stick; the right
     * half is the look zone. View / Xbox / Menu live in the YugiPC menu.
     */
    private void setDefaults(boolean landscape) {
        controls.clear();
        if (landscape) {
            controls.add(c("lt", T_BUTTON, LT, "LT", 0.07f, 0.10f, 52));
            controls.add(c("lb", T_BUTTON, LB, "LB", 0.07f, 0.32f, 52));
            controls.add(c("ls", T_STICK, 0, "", 0.18f, 0.62f, 120));
            controls.add(c("l3", T_BUTTON, LS, "L3", 0.05f, 0.90f, 44));
            controls.add(c("dpad", T_DPAD, 0, "", 0.36f, 0.84f, 100));
            controls.add(c("rt", T_BUTTON, RT, "RT", 0.93f, 0.10f, 56));
            controls.add(c("rb", T_BUTTON, RB, "RB", 0.93f, 0.32f, 52));
            controls.add(c("y", T_BUTTON, Y, "Y", 0.83f, 0.40f, 54));
            controls.add(c("x", T_BUTTON, X, "X", 0.76f, 0.60f, 54));
            controls.add(c("b", T_BUTTON, B, "B", 0.90f, 0.60f, 54));
            controls.add(c("a", T_BUTTON, A, "A", 0.83f, 0.80f, 54));
            controls.add(c("r3", T_BUTTON, RS, "R3", 0.66f, 0.88f, 44));
        } else {
            controls.add(c("lt", T_BUTTON, LT, "LT", 0.10f, 0.42f, 52));
            controls.add(c("lb", T_BUTTON, LB, "LB", 0.27f, 0.42f, 52));
            controls.add(c("rt", T_BUTTON, RT, "RT", 0.90f, 0.42f, 56));
            controls.add(c("rb", T_BUTTON, RB, "RB", 0.73f, 0.42f, 52));
            controls.add(c("ls", T_STICK, 0, "", 0.25f, 0.66f, 120));
            controls.add(c("y", T_BUTTON, Y, "Y", 0.80f, 0.56f, 54));
            controls.add(c("x", T_BUTTON, X, "X", 0.67f, 0.65f, 54));
            controls.add(c("b", T_BUTTON, B, "B", 0.93f, 0.65f, 54));
            controls.add(c("a", T_BUTTON, A, "A", 0.80f, 0.74f, 54));
            controls.add(c("dpad", T_DPAD, 0, "", 0.24f, 0.90f, 100));
            controls.add(c("l3", T_BUTTON, LS, "L3", 0.08f, 0.80f, 44));
            controls.add(c("r3", T_BUTTON, RS, "R3", 0.80f, 0.92f, 44));
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("yugipc_pad", Context.MODE_PRIVATE);
    }

    /** Loads the layout saved for this screen (e.g. "cover_l", "inner_p"), or the defaults. */
    public void loadLayout(String key, boolean landscape) {
        layoutKey = key;
        setDefaults(landscape);
        String json = prefs().getString("layout3_" + key, null);
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
            prefs().edit().putString("layout3_" + layoutKey, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void resetLayout(boolean landscape) {
        prefs().edit().remove("layout3_" + layoutKey).apply();
        setDefaults(landscape);
        selected = null;
        if (editListener != null) editListener.onSelectionChanged(null);
        invalidate();
    }

    // ---------------------------------------------------------------- settings

    public PadSettings getSettings() { return settings; }

    public void applySettings(PadSettings s) {
        settings = s;
        updateGyro();
    }

    private void updateGyro() {
        boolean want = settings.gyro && padEnabled && !editing && isAttachedToWindow();
        if (sensors == null) sensors = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return;
        sensors.unregisterListener(this);
        gyroX = gyroY = 0;
        if (want) {
            Sensor g = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
            startTicking();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int rot = 0;
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            rot = wm.getDefaultDisplay().getRotation();
        } catch (Exception ignored) {}
        float[] v = PadMath.gyroToLook(e.values[0], e.values[1], e.values[2], rot,
                settings.gyroSensitivity, settings.invertY);
        gyroX = v[0];
        gyroY = v[1];
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateGyro();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sensors != null) sensors.unregisterListener(this);
        ticking = false;
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- frame ticker (look decay + gyro)

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!ticking) return;
            if (lookPointer == -1 || now - lookLastMoveNs > 2 * TICK_NS) {
                lookX = PadMath.decay(lookX, settings.lookDecay);
                lookY = PadMath.decay(lookY, settings.lookDecay);
            }
            recompute();
            boolean keep = lookX != 0 || lookY != 0 || lookPointer != -1 || (settings.gyro && padEnabled);
            if (keep) Choreographer.getInstance().postFrameCallback(this);
            else ticking = false;
        }
    };

    private void startTicking() {
        if (!ticking) {
            ticking = true;
            Choreographer.getInstance().postFrameCallback(tick);
        }
    }

    // ---------------------------------------------------------------- edit mode

    public void setEditListener(EditListener l) { editListener = l; }
    public boolean isEditing() { return editing; }

    public void setEditing(boolean on) {
        editing = on;
        selected = null;
        releaseAll();
        updateGyro();
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
        updateGyro();
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
        for (Control c : controls) { c.pointerId = -1; c.dpadMask = 0; }
        byPointer.clear();
        leftPointer = lookPointer = -1;
        leftX = leftY = lookX = lookY = 0;
        publish();
    }

    /** Presses a button briefly (menu actions such as View / Xbox / Menu). */
    public void pulse(int button, long ms) {
        buttons[button] = true;
        publish();
        postDelayed(() -> { buttons[button] = false; recompute(); }, ms);
    }

    // ---------------------------------------------------------------- geometry

    /** Keeps every control below this line (the YugiPC toolbar / HUD). */
    public void setTopInset(int px) {
        if (px != topInset) { topInset = px; invalidate(); }
    }

    private float radiusPx(Control c) { return dp(c.sizeDp) / 2f; }

    private float cxPx(Control c) {
        float r = radiusPx(c), w = getWidth();
        return Math.max(r, Math.min(w - r, c.cx * w));
    }

    private float cyPx(Control c) {
        float r = radiusPx(c), h = getHeight();
        float y = topInset + c.cy * (h - topInset);
        return Math.max(topInset + r, Math.min(h - r, y));
    }

    private Control stickControl() {
        for (Control c : controls) if (c.type == T_STICK) return c;
        return null;
    }

    /** Bounding boxes of all controls (for overlap checks). */
    public List<Rect> controlRects() {
        List<Rect> out = new ArrayList<>();
        for (Control c : controls) {
            float x = cxPx(c), y = cyPx(c), r = radiusPx(c);
            out.add(new Rect(Math.round(x - r), Math.round(y - r), Math.round(x + r), Math.round(y + r)));
        }
        return out;
    }

    public List<String> controlIds() {
        List<String> out = new ArrayList<>();
        for (Control c : controls) out.add(c.id);
        return out;
    }

    /** Selected control's center y (px) or -1, so the editor card can stay out of its way. */
    public float selectedCenterY() { return selected == null ? -1 : cyPx(selected); }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (editing) canvas.drawColor(0x66000000);
        for (Control c : controls) {
            float x = cxPx(c), y = cyPx(c), r = radiusPx(c);
            int a = (int) (255 * c.alpha);
            boolean pressed = c.pointerId != -1;
            fill.setColor(Color.argb((int) (a * (pressed ? 0.85f : 0.5f)), 14, 17, 24));
            stroke.setColor(Color.argb(Math.min(255, a + 30), 255, 255, 255));
            if (c.type == T_STICK) {
                // Floating stick: drawn where the thumb is; otherwise a faint resting hint
                boolean active = leftPointer != -1 && !editing;
                float sx = active ? leftOx : x, sy = active ? leftOy : y;
                fill.setColor(Color.argb((int) (a * (active ? 0.6f : 0.25f)), 14, 17, 24));
                stroke.setColor(Color.argb((int) (a * (active ? 1f : 0.5f)), 255, 255, 255));
                canvas.drawCircle(sx, sy, r, fill);
                canvas.drawCircle(sx, sy, r, stroke);
                fill.setColor(Color.argb(Math.min(255, a + 40), 235, 236, 240));
                canvas.drawCircle(sx + leftX * r * 0.6f, sy + leftY * r * 0.6f, r * 0.38f, fill);
            } else if (c.type == T_DPAD) {
                float arm = r * 0.36f;
                RectF h = new RectF(x - r, y - arm, x + r, y + arm);
                RectF v = new RectF(x - arm, y - r, x + arm, y + r);
                canvas.drawRoundRect(h, arm * 0.4f, arm * 0.4f, fill);
                canvas.drawRoundRect(v, arm * 0.4f, arm * 0.4f, fill);
                canvas.drawRoundRect(h, arm * 0.4f, arm * 0.4f, stroke);
                canvas.drawRoundRect(v, arm * 0.4f, arm * 0.4f, stroke);
                fill.setColor(Color.argb(a, 235, 236, 240));
                if ((c.dpadMask & 1) != 0) canvas.drawCircle(x, y - r * 0.65f, arm * 0.6f, fill);
                if ((c.dpadMask & 2) != 0) canvas.drawCircle(x, y + r * 0.65f, arm * 0.6f, fill);
                if ((c.dpadMask & 4) != 0) canvas.drawCircle(x - r * 0.65f, y, arm * 0.6f, fill);
                if ((c.dpadMask & 8) != 0) canvas.drawCircle(x + r * 0.65f, y, arm * 0.6f, fill);
            } else {
                canvas.drawCircle(x, y, r, fill);
                canvas.drawCircle(x, y, r, stroke);
                text.setTextSize(r * 0.62f);
                text.setAlpha(Math.min(255, a + 70));
                canvas.drawText(c.label, x, y + r * 0.22f, text);
            }
        }
        if (editing) {
            for (Control c : controls) {
                float x = cxPx(c), y = cyPx(c), r = radiusPx(c) + dp(5);
                boolean sel = c == selected;
                editPaint.setStrokeWidth(dp(sel ? 3 : 1.5f));
                editPaint.setColor(sel ? YugiTheme.ACCENT : 0xB3FFFFFF);
                editPaint.setPathEffect(sel ? null : new DashPathEffect(new float[]{dp(6), dp(5)}, 0));
                canvas.drawCircle(x, y, r, editPaint);
            }
            editPaint.setPathEffect(null);
        }
    }

    // ---------------------------------------------------------------- touch

    /** Buttons / d-pad under the finger (the stick hint is not a button). */
    private Control hitButton(float x, float y) {
        Control best = null;
        float bestD = Float.MAX_VALUE;
        for (Control c : controls) {
            if (c.type == T_STICK) continue;
            float dx = x - cxPx(c), dy = y - cyPx(c);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float reach = radiusPx(c) * (c.type == T_BUTTON ? 1.15f : 1.05f);
            if (d <= reach && d < bestD) { best = c; bestD = d; }
        }
        return best;
    }

    private Control hitAny(float x, float y) {
        Control b = hitButton(x, y);
        if (b != null) return b;
        Control s = stickControl();
        if (s != null) {
            float dx = x - cxPx(s), dy = y - cyPx(s);
            if (Math.sqrt(dx * dx + dy * dy) <= radiusPx(s)) return s;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return onEditTouch(e);

        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            // Deliver every move immediately (no batching to vsync)
            requestUnbufferedDispatch(e);
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                int id = e.getPointerId(idx);
                if (y < topInset) return action == MotionEvent.ACTION_POINTER_DOWN;
                Control c = hitButton(x, y);
                if (c != null && c.pointerId == -1) {
                    c.pointerId = id;
                    byPointer.put(id, c);
                    if (c.type == T_DPAD) applyDpad(c, x, y);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (x < getWidth() / 2f) {
                    if (leftPointer == -1) {
                        leftPointer = id;
                        leftOx = x;
                        leftOy = y;
                        leftX = leftY = 0;
                    }
                } else if (lookPointer == -1) {
                    lookPointer = id;
                    lookLastX = x;
                    lookLastY = y;
                    lookLastT = e.getEventTime();
                    lookLastMoveNs = System.nanoTime();
                    startTicking();
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (id == leftPointer) {
                        moveLeft(x, y);
                    } else if (id == lookPointer) {
                        moveLook(x, y, e.getEventTime());
                    } else {
                        Control c = byPointer.get(id);
                        if (c != null && c.type == T_DPAD) applyDpad(c, x, y);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id == leftPointer) { leftPointer = -1; leftX = leftY = 0; }
                else if (id == lookPointer) { lookPointer = -1; }
                else {
                    Control c = byPointer.get(id);
                    if (c != null) { c.pointerId = -1; c.dpadMask = 0; byPointer.remove(id); }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                invalidate();
                return true;
        }
        recompute();
        invalidate();
        return true;
    }

    private void moveLeft(float x, float y) {
        Control s = stickControl();
        float r = s != null ? radiusPx(s) : dp(60);
        float dx = x - leftOx, dy = y - leftOy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > r) {
            // The base follows the thumb: full deflection keeps working without jumps
            leftOx = x - dx / len * r;
            leftOy = y - dy / len * r;
            dx = x - leftOx;
            dy = y - leftOy;
        }
        float[] v = PadMath.stick(dx, dy, r, settings.stickDeadzone);
        leftX = v[0];
        leftY = v[1];
    }

    private void moveLook(float x, float y, long t) {
        float density = getResources().getDisplayMetrics().density;
        float dxDp = (x - lookLastX) / density, dyDp = (y - lookLastY) / density;
        float dt = Math.max(1, t - lookLastT);
        if (dxDp != 0 || dyDp != 0) {
            lookX = PadMath.look(dxDp, dt, settings.lookSensitivity, settings.lookCurve, settings.lookAntiDeadzone);
            float ly = PadMath.look(dyDp, dt, settings.lookSensitivity, settings.lookCurve, settings.lookAntiDeadzone);
            lookY = settings.invertY ? -ly : ly;
            lookLastMoveNs = System.nanoTime();
        }
        lookLastX = x;
        lookLastY = y;
        lookLastT = t;
    }

    private void applyDpad(Control c, float x, float y) {
        float r = radiusPx(c);
        float dx = (x - cxPx(c)) / r, dy = (y - cyPx(c)) / r;
        int m = 0;
        float dead = 0.25f;
        if (dy < -dead && Math.abs(dy) >= Math.abs(dx) * 0.4f) m |= 1;
        if (dy > dead && Math.abs(dy) >= Math.abs(dx) * 0.4f) m |= 2;
        if (dx < -dead && Math.abs(dx) >= Math.abs(dy) * 0.4f) m |= 4;
        if (dx > dead && Math.abs(dx) >= Math.abs(dy) * 0.4f) m |= 8;
        if (m != c.dpadMask) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        c.dpadMask = m;
    }

    private void recompute() {
        for (int i = 0; i < BUTTON_COUNT; i++) buttons[i] = false;
        for (Control c : controls) {
            if (c.pointerId == -1) continue;
            if (c.type == T_BUTTON) buttons[c.button] = true;
            else if (c.type == T_DPAD) {
                buttons[UP] |= (c.dpadMask & 1) != 0;
                buttons[DOWN] |= (c.dpadMask & 2) != 0;
                buttons[LEFT] |= (c.dpadMask & 4) != 0;
                buttons[RIGHT] |= (c.dpadMask & 8) != 0;
            }
        }
        axes[0] = leftX;
        axes[1] = leftY;
        axes[2] = PadMath.clamp1(lookX + gyroX);
        axes[3] = PadMath.clamp1(lookY + gyroY);
        publish();
    }

    private boolean onEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                Control c = hitAny(e.getX(), e.getY());
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
                        float frameH = Math.max(1, getHeight() - topInset);
                        selected.cy = clamp((e.getY(i) + dragDy - topInset) / frameH);
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

    private static float clamp(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
}
