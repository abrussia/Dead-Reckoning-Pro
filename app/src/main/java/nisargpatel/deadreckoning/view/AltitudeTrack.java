package nisargpatel.deadreckoning.view;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a track with its height shown literally: every point is lifted up (or pushed down)
 * on the screen by its barometric altitude relative to the start, at the map's own scale.
 *
 *  - thin dashed line: where you walked on the ground (plan view);
 *  - thick line: the same track raised / lowered by its height;
 *  - translucent band + vertical ticks between them: the height itself
 *    (red above the start level, blue below it).
 */
public final class AltitudeTrack {

    /** 1 = true scale (1 m of height = 1 m on the map). */
    private static final float EXAGGERATION = 1.0f;

    private final MapView mapView;
    private final Overlay anchor;
    private final float strokeWidth;
    private final ElevationOverlay overlay;

    private double min = 0;
    private double max = 0;

    public AltitudeTrack(MapView mapView, Overlay anchor, float strokeWidth) {
        this.mapView = mapView;
        this.anchor = anchor;
        this.strokeWidth = strokeWidth;
        this.overlay = new ElevationOverlay();
    }

    public void clear() {
        mapView.getOverlays().remove(overlay);
        overlay.points = new ArrayList<>();
    }

    public void setPoints(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            clear();
            return;
        }
        min = Double.MAX_VALUE;
        max = -Double.MAX_VALUE;
        for (GeoPoint p : points) {
            double a = alt(p);
            if (a < min) min = a;
            if (a > max) max = a;
        }
        overlay.points = new ArrayList<>(points);
        if (!mapView.getOverlays().contains(overlay)) {
            int at = mapView.getOverlays().indexOf(anchor);
            if (at < 0) at = mapView.getOverlays().size();
            mapView.getOverlays().add(at, overlay);
        }
    }

    public boolean isEmpty() {
        return overlay.points.isEmpty();
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public String getRangeText() {
        return String.format(Locale.US, "%+.1f…%+.1f m", min, max);
    }

    private static double alt(GeoPoint p) {
        double a = p.getAltitude();
        return Double.isNaN(a) ? 0.0 : a;
    }

    /** True if the track has a noticeable altitude change worth showing. */
    public static boolean hasAltitudeVariation(List<GeoPoint> points) {
        if (points == null || points.size() < 2) return false;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (GeoPoint p : points) {
            double a = alt(p);
            if (a < lo) lo = a;
            if (a > hi) hi = a;
        }
        return hi - lo >= 0.5;
    }

    private final class ElevationOverlay extends Overlay {

        List<GeoPoint> points = new ArrayList<>();

        private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Point tmp = new Point();
        private final Point scr = new Point();

        ElevationOverlay() {
            groundPaint.setStyle(Paint.Style.STROKE);
            groundPaint.setColor(Color.argb(200, 90, 90, 90));
            groundPaint.setStrokeWidth(Math.max(3f, strokeWidth / 3f));
            groundPaint.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0));

            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(strokeWidth);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);

            bandPaint.setStyle(Paint.Style.FILL);

            tickPaint.setStyle(Paint.Style.STROKE);
            tickPaint.setStrokeWidth(2f);

            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(36f);
            textPaint.setFakeBoldText(true);
            textHalo.set(textPaint);
            textHalo.setColor(Color.WHITE);
            textHalo.setStyle(Paint.Style.STROKE);
            textHalo.setStrokeWidth(8f);
        }

        @Override
        public void draw(Canvas canvas, Projection pj) {
            if (points.size() < 2) return;

            int n = points.size();
            float[] gx = new float[n];
            float[] gy = new float[n];
            float[] ex = new float[n];
            float[] ey = new float[n];
            float pxPerMeter = pj.metersToPixels(1f) * EXAGGERATION;

            for (int i = 0; i < n; i++) {
                GeoPoint g = points.get(i);
                pj.toPixels(g, tmp);
                // to real screen coordinates (map rotation applied)
                pj.rotateAndScalePoint(tmp.x, tmp.y, scr);
                gx[i] = scr.x;
                gy[i] = scr.y;
                ex[i] = scr.x;
                ey[i] = (float) (scr.y - alt(g) * pxPerMeter); // up on screen = higher
            }

            // Draw in screen space so "up" is always up, whatever the map rotation.
            pj.save(canvas, false, false);
            try {
                // ground track
                Path ground = new Path();
                ground.moveTo(gx[0], gy[0]);
                for (int i = 1; i < n; i++) ground.lineTo(gx[i], gy[i]);
                canvas.drawPath(ground, groundPaint);

                // band + ticks + elevated line, segment by segment, coloured by sign
                float lastTickX = Float.NaN;
                float lastTickY = Float.NaN;
                for (int i = 1; i < n; i++) {
                    double a0 = alt(points.get(i - 1));
                    double a1 = alt(points.get(i));
                    boolean above = (a0 + a1) / 2.0 >= 0;
                    int base = above ? Color.rgb(211, 47, 47) : Color.rgb(25, 118, 210);

                    Path band = new Path();
                    band.moveTo(gx[i - 1], gy[i - 1]);
                    band.lineTo(ex[i - 1], ey[i - 1]);
                    band.lineTo(ex[i], ey[i]);
                    band.lineTo(gx[i], gy[i]);
                    band.close();
                    bandPaint.setColor(Color.argb(60, Color.red(base), Color.green(base), Color.blue(base)));
                    canvas.drawPath(band, bandPaint);

                    // vertical ticks, at least ~14 px apart
                    if (Float.isNaN(lastTickX)
                            || Math.hypot(gx[i] - lastTickX, gy[i] - lastTickY) > 14) {
                        tickPaint.setColor(Color.argb(150, Color.red(base), Color.green(base), Color.blue(base)));
                        canvas.drawLine(gx[i], gy[i], ex[i], ey[i], tickPaint);
                        lastTickX = gx[i];
                        lastTickY = gy[i];
                    }

                    linePaint.setColor(base);
                    canvas.drawLine(ex[i - 1], ey[i - 1], ex[i], ey[i], linePaint);
                }

                // current height label at the end of the track
                double last = alt(points.get(n - 1));
                String label = String.format(Locale.US, "%+.1f m", last);
                float tx = ex[n - 1] + strokeWidth;
                float ty = ey[n - 1] - strokeWidth;
                canvas.drawText(label, tx, ty, textHalo);
                canvas.drawText(label, tx, ty, textPaint);
            } finally {
                pj.restore(canvas, false);
            }
        }
    }
}
