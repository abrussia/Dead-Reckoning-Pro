package nisargpatel.deadreckoning.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import org.osmdroid.util.GeoPoint;

import java.util.List;
import java.util.Locale;

/**
 * Elevation profile: height (relative to the start) against distance walked.
 * The curve uses the same blue (low) → red (high) colours as the track on the map.
 * A dashed line marks the start level (0 m).
 */
public class ElevationProfileView extends View {

    private float[] dist = new float[0];
    private float[] alt = new float[0];
    private double lo = 0;
    private double hi = 0;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;

    public ElevationProfileView(Context context) {
        this(context, null);
    }

    public ElevationProfileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());

        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        bgPaint.setColor(night ? Color.argb(235, 30, 34, 39) : Color.argb(230, 255, 255, 255));
        bgPaint.setShadowLayer(3 * dp, 0, 1 * dp, Color.argb(60, 0, 0, 0));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3 * dp);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setStrokeWidth(1.2f * dp);
        zeroPaint.setColor(night ? Color.argb(170, 200, 200, 200) : Color.argb(160, 60, 60, 60));
        zeroPaint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));

        textPaint.setColor(night ? Color.rgb(170, 178, 186) : Color.rgb(80, 80, 80));
        textPaint.setTextSize(11 * dp);

        boldPaint.setColor(night ? Color.rgb(236, 239, 241) : Color.rgb(30, 30, 30));
        boldPaint.setTextSize(14 * dp);
        boldPaint.setFakeBoldText(true);

        dotPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_SOFTWARE, null); // for the shadow
        setVisibility(GONE);
    }

    /** Show the profile of these points (altitude in GeoPoint.getAltitude()). Hides itself if flat/empty. */
    public void setPoints(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            setVisibility(GONE);
            return;
        }
        int n = points.size();
        float[] d = new float[n];
        float[] a = new float[n];
        double total = 0;
        double mn = Double.MAX_VALUE;
        double mx = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            GeoPoint p = points.get(i);
            if (i > 0) total += points.get(i - 1).distanceToAsDouble(p);
            double h = p.getAltitude();
            if (Double.isNaN(h)) h = 0;
            d[i] = (float) total;
            a[i] = (float) h;
            mn = Math.min(mn, h);
            mx = Math.max(mx, h);
        }
        dist = d;
        alt = a;
        lo = Math.min(mn, 0);
        hi = Math.max(mx, 0);
        if (hi - lo < 2.0) {
            double mid = (hi + lo) / 2.0;
            lo = mid - 1.0;
            hi = mid + 1.0;
        }
        setVisibility(VISIBLE);
        invalidate();
    }

    public void clear() {
        dist = new float[0];
        alt = new float[0];
        setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = 3 * dp;
        rect.set(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, bgPaint);

        int n = dist.length;
        if (n < 2) return;

        // plot area
        float left = rect.left + 48 * dp;
        float right = rect.right - 12 * dp;
        float top = rect.top + 26 * dp;
        float bottom = rect.bottom - 18 * dp;
        float maxD = Math.max(1f, dist[n - 1]);

        // header: current height and range
        float cur = alt[n - 1];
        canvas.drawText(getContext().getString(nisargpatel.deadreckoning.R.string.profile_height, cur), rect.left + 12 * dp, rect.top + 18 * dp, boldPaint);
        String range = getContext().getString(nisargpatel.deadreckoning.R.string.profile_range, minOf(alt), maxOf(alt));
        float rw = textPaint.measureText(range);
        canvas.drawText(range, rect.right - 12 * dp - rw, rect.top + 18 * dp, textPaint);

        // axis labels
        canvas.drawText(getContext().getString(nisargpatel.deadreckoning.R.string.profile_axis, hi), rect.left + 8 * dp, top + 10 * dp, textPaint);
        canvas.drawText(getContext().getString(nisargpatel.deadreckoning.R.string.profile_axis, lo), rect.left + 8 * dp, bottom, textPaint);
        String dl = getContext().getString(nisargpatel.deadreckoning.R.string.profile_walked, dist[n - 1]);
        canvas.drawText(dl, right - textPaint.measureText(dl), rect.bottom - 4 * dp, textPaint);

        // start level
        float y0 = yFor(0, top, bottom);
        canvas.drawLine(left, y0, right, y0, zeroPaint);

        // vertical gradient matching the map colours (top = high = red, bottom = low = blue)
        Shader shader = new LinearGradient(0, top, 0, bottom,
                new int[]{AltitudeTrack.colorAt(1f), AltitudeTrack.colorAt(0.5f), AltitudeTrack.colorAt(0f)},
                null, Shader.TileMode.CLAMP);
        linePaint.setShader(shader);
        Shader fillShader = new LinearGradient(0, top, 0, bottom,
                new int[]{withAlpha(AltitudeTrack.colorAt(1f), 70), withAlpha(AltitudeTrack.colorAt(0f), 70)},
                null, Shader.TileMode.CLAMP);
        fillPaint.setShader(fillShader);

        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < n; i++) {
            float x = left + (right - left) * dist[i] / maxD;
            float y = yFor(alt[i], top, bottom);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, y0);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(right, y0);
        fill.close();
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);

        // current position dot
        float cx = right;
        float cy = yFor(cur, top, bottom);
        dotPaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 6 * dp, dotPaint);
        dotPaint.setColor(AltitudeTrack.colorAt((float) ((cur - lo) / (hi - lo))));
        canvas.drawCircle(cx, cy, 4 * dp, dotPaint);
    }

    private float yFor(double a, float top, float bottom) {
        return (float) (bottom - (a - lo) / (hi - lo) * (bottom - top));
    }

    private static float minOf(float[] v) {
        float m = Float.MAX_VALUE;
        for (float x : v) m = Math.min(m, x);
        return m;
    }

    private static float maxOf(float[] v) {
        float m = -Float.MAX_VALUE;
        for (float x : v) m = Math.max(m, x);
        return m;
    }

    private static int withAlpha(int c, int a) {
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c));
    }
}
