package nisargpatel.deadreckoning.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.osmdroid.util.GeoPoint;

import java.util.List;
import java.util.Locale;

/**
 * Interactive 3D view of a track: x = east, y = north, z = barometric height.
 * One finger drag rotates (left/right) and tilts (up/down), pinch zooms,
 * double tap resets the view. Heights can be exaggerated.
 */
public class Track3DView extends View {

    private static final float DEFAULT_YAW = 0f;
    private static final float DEFAULT_PITCH = 55f;

    private double[] px = new double[0];
    private double[] py = new double[0];
    private double[] pz = new double[0];
    private double minZ = 0;
    private double maxZ = 0;
    private double extent = 1;   // horizontal size of the track, metres
    private double floorZ = 0;   // height of the reference grid

    private float yaw = DEFAULT_YAW;     // degrees, rotation around the vertical axis
    private float pitch = DEFAULT_PITCH; // 0 = from above, 90 = from the side
    private float zoom = 1f;
    private float panX = 0f;
    private float panY = 0f;
    private float exaggeration = 1f;

    private final float dp;
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float lastX;
    private float lastY;
    private int activePointers = 0;

    public Track3DView(Context context) {
        this(context, null);
    }

    public Track3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * dp);
        gridPaint.setColor(Color.argb(70, 120, 140, 160));

        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeWidth(2f * dp);
        shadowPaint.setColor(Color.argb(110, 90, 100, 110));

        dropPaint.setStyle(Paint.Style.STROKE);
        dropPaint.setStrokeWidth(1f * dp);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(4f * dp);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.rgb(220, 225, 230));
        textPaint.setTextSize(13 * dp);

        northPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        northPaint.setStrokeWidth(2 * dp);
        northPaint.setColor(Color.rgb(239, 83, 80));
        northPaint.setTextSize(13 * dp);
        northPaint.setFakeBoldText(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoom *= d.getScaleFactor();
                zoom = Math.max(0.2f, Math.min(zoom, 30f));
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetView();
                return true;
            }
        });
    }

    public void setPoints(List<GeoPoint> points) {
        if (points == null || points.isEmpty()) {
            px = py = pz = new double[0];
            invalidate();
            return;
        }
        int n = points.size();
        px = new double[n];
        py = new double[n];
        pz = new double[n];
        GeoPoint o = points.get(0);
        double lat0 = Math.toRadians(o.getLatitude());
        double r = 6371000.0;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        minZ = Double.MAX_VALUE;
        maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            GeoPoint p = points.get(i);
            px[i] = Math.toRadians(p.getLongitude() - o.getLongitude()) * r * Math.cos(lat0);
            py[i] = Math.toRadians(p.getLatitude() - o.getLatitude()) * r;
            double a = p.getAltitude();
            pz[i] = Double.isNaN(a) ? 0 : a;
            minX = Math.min(minX, px[i]);
            maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]);
            maxY = Math.max(maxY, py[i]);
            minZ = Math.min(minZ, pz[i]);
            maxZ = Math.max(maxZ, pz[i]);
        }
        // centre the track horizontally
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        for (int i = 0; i < n; i++) {
            px[i] -= cx;
            py[i] -= cy;
        }
        extent = Math.max(5.0, Math.max(maxX - minX, maxY - minY));
        floorZ = Math.min(minZ, 0);
        // pick an exaggeration that makes small height changes visible
        double range = maxZ - minZ;
        if (range < extent * 0.02) exaggeration = 10f;
        else if (range < extent * 0.05) exaggeration = 5f;
        else if (range < extent * 0.15) exaggeration = 2f;
        else exaggeration = 1f;
        resetView();
    }

    public float getExaggeration() {
        return exaggeration;
    }

    public void setExaggeration(float e) {
        exaggeration = e;
        invalidate();
    }

    public void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1f;
        panX = 0;
        panY = 0;
        invalidate();
    }

    public void setTopView() {
        pitch = 0;
        invalidate();
    }

    public void setSideView() {
        pitch = 89;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                activePointers = 1;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                activePointers = e.getPointerCount();
                lastX = avgX(e);
                lastY = avgY(e);
                break;
            case MotionEvent.ACTION_MOVE: {
                float x = activePointers > 1 ? avgX(e) : e.getX();
                float y = activePointers > 1 ? avgY(e) : e.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                if (activePointers > 1) {
                    // two fingers: move the picture
                    panX += dx;
                    panY += dy;
                } else if (!scaleDetector.isInProgress()) {
                    yaw += dx * 0.4f;
                    pitch += dy * 0.3f;
                    pitch = Math.max(0f, Math.min(89f, pitch));
                }
                lastX = x;
                lastY = y;
                invalidate();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                activePointers = e.getPointerCount() - 1;
                int keep = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(keep);
                lastY = e.getY(keep);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointers = 0;
                break;
        }
        return true;
    }

    private static float avgX(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private static float avgY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    // --- projection --------------------------------------------------------

    private float scale;
    private float cx0;
    private float cy0;
    private double cosYaw, sinYaw, cosPitch, sinPitch;
    private double zMid;

    private void setupProjection() {
        float w = getWidth();
        float h = getHeight();
        scale = (float) (Math.min(w, h) * 0.8 / extent) * zoom;
        cx0 = w / 2f + panX;
        cy0 = h / 2f + panY;
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        cosYaw = Math.cos(y);
        sinYaw = Math.sin(y);
        cosPitch = Math.cos(p);
        sinPitch = Math.sin(p);
        zMid = (floorZ + maxZ) / 2.0;
    }

    private float sx(double x, double y) {
        return (float) (cx0 + (x * cosYaw - y * sinYaw) * scale);
    }

    private float sy(double x, double y, double z) {
        double y1 = x * sinYaw + y * cosYaw;
        double z1 = (z - zMid) * exaggeration;
        double up = y1 * cosPitch + z1 * sinPitch;
        return (float) (cy0 - up * scale);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(24, 28, 34));
        int n = px.length;
        if (n < 2) {
            canvas.drawText(getContext().getString(nisargpatel.deadreckoning.R.string.v3d_empty), 16 * dp, 40 * dp, textPaint);
            return;
        }
        setupProjection();

        // grid on the floor (lowest level or start level, whichever is lower)
        double step = niceStep(extent / 6.0);
        double half = Math.ceil(extent * 0.6 / step) * step;
        for (double g = -half; g <= half + 1e-6; g += step) {
            canvas.drawLine(sx(g, -half), sy(g, -half, floorZ), sx(g, half), sy(g, half, floorZ), gridPaint);
            canvas.drawLine(sx(-half, g), sy(-half, g, floorZ), sx(half, g), sy(half, g, floorZ), gridPaint);
        }

        // track shadow on the floor
        Path shadow = new Path();
        shadow.moveTo(sx(px[0], py[0]), sy(px[0], py[0], floorZ));
        for (int i = 1; i < n; i++) shadow.lineTo(sx(px[i], py[i]), sy(px[i], py[i], floorZ));
        canvas.drawPath(shadow, shadowPaint);

        double lo = Math.min(minZ, 0);
        double hi = Math.max(maxZ, 0);
        if (hi - lo < 2) {
            double mid = (hi + lo) / 2;
            lo = mid - 1;
            hi = mid + 1;
        }

        // vertical drop lines, about every 2% of the track size
        double dropEvery = extent / 50.0;
        double acc = dropEvery;
        for (int i = 1; i < n; i++) {
            acc += Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
            if (acc >= dropEvery) {
                acc = 0;
                int c = AltitudeTrack.colorAt((float) ((pz[i] - lo) / (hi - lo)));
                dropPaint.setColor(Color.argb(90, Color.red(c), Color.green(c), Color.blue(c)));
                float x = sx(px[i], py[i]);
                canvas.drawLine(x, sy(px[i], py[i], floorZ), x, sy(px[i], py[i], pz[i]), dropPaint);
            }
        }

        // the track itself, coloured by height
        for (int i = 1; i < n; i++) {
            double zAvg = (pz[i - 1] + pz[i]) / 2.0;
            trackPaint.setColor(AltitudeTrack.colorAt((float) ((zAvg - lo) / (hi - lo))));
            canvas.drawLine(sx(px[i - 1], py[i - 1]), sy(px[i - 1], py[i - 1], pz[i - 1]),
                    sx(px[i], py[i]), sy(px[i], py[i], pz[i]), trackPaint);
        }

        // start (green) and end (red) markers
        drawDot(canvas, sx(px[0], py[0]), sy(px[0], py[0], pz[0]), Color.rgb(67, 160, 71));
        drawDot(canvas, sx(px[n - 1], py[n - 1]), sy(px[n - 1], py[n - 1], pz[n - 1]), Color.rgb(229, 57, 53));

        // north arrow (on the floor plane) in the top right corner
        float ax = getWidth() - 40 * dp;
        float ay = 70 * dp;
        float nx = (float) (-sinYaw);   // screen direction of north (0,1) after yaw
        float ny = (float) (cosYaw * cosPitch);
        float len = (float) Math.hypot(nx, ny);
        if (len > 0.05f) {
            nx /= len;
            ny /= len;
            float l = 22 * dp;
            canvas.drawLine(ax - nx * l, ay + ny * l, ax + nx * l, ay - ny * l, northPaint);
            canvas.drawCircle(ax + nx * l, ay - ny * l, 4 * dp, northPaint);
            canvas.drawText("N", ax + nx * (l + 12 * dp) - 5 * dp, ay - ny * (l + 12 * dp) + 5 * dp, northPaint);
        }

        // info
        float ty = 24 * dp;
        canvas.drawText(getContext().getString(nisargpatel.deadreckoning.R.string.v3d_info,
                minZ, maxZ, trim(step), trim(exaggeration)), 12 * dp, ty, textPaint);
        canvas.drawText(getContext().getString(nisargpatel.deadreckoning.R.string.v3d_hint),
                12 * dp, getHeight() - 12 * dp, textPaint);
    }

    private void drawDot(Canvas c, float x, float y, int color) {
        dotPaint.setColor(Color.WHITE);
        c.drawCircle(x, y, 7 * dp, dotPaint);
        dotPaint.setColor(color);
        c.drawCircle(x, y, 5 * dp, dotPaint);
    }

    private static double niceStep(double raw) {
        double p = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1e-3))));
        double m = raw / p;
        if (m < 1.5) return p;
        if (m < 3.5) return 2 * p;
        if (m < 7.5) return 5 * p;
        return 10 * p;
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-6) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.1f", v);
    }
}
