package nisargpatel.deadreckoning.view;

import android.graphics.Color;
import android.graphics.Paint;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a track coloured by barometric height relative to the start:
 * blue = lowest, red = highest (same scale as {@link ElevationProfileView}).
 * The track is split into polylines, one per colour band.
 */
public final class AltitudeTrack {

    private static final int BANDS = 16;
    private static final double MIN_RANGE_M = 2.0;

    private final MapView mapView;
    private final Overlay anchor;
    private final float strokeWidth;
    private final List<Polyline> segments = new ArrayList<>();

    private double min = 0;
    private double max = 0;

    public AltitudeTrack(MapView mapView, Overlay anchor, float strokeWidth) {
        this.mapView = mapView;
        this.anchor = anchor;
        this.strokeWidth = strokeWidth;
    }

    public void clear() {
        for (Polyline p : segments) {
            mapView.getOverlays().remove(p);
        }
        segments.clear();
    }

    public void setPoints(List<GeoPoint> points) {
        clear();
        if (points == null || points.size() < 2) {
            return;
        }

        min = Double.MAX_VALUE;
        max = -Double.MAX_VALUE;
        for (GeoPoint p : points) {
            double a = alt(p);
            if (a < min) min = a;
            if (a > max) max = a;
        }
        double lo = Math.min(min, 0);
        double hi = Math.max(max, 0);
        if (hi - lo < MIN_RANGE_M) {
            double mid = (hi + lo) / 2.0;
            lo = mid - MIN_RANGE_M / 2.0;
            hi = mid + MIN_RANGE_M / 2.0;
        }

        int insertAt = mapView.getOverlays().indexOf(anchor);
        if (insertAt < 0) insertAt = mapView.getOverlays().size();

        List<GeoPoint> current = new ArrayList<>();
        int currentBand = band(alt(points.get(0)), lo, hi);
        current.add(points.get(0));

        for (int i = 1; i < points.size(); i++) {
            GeoPoint p = points.get(i);
            int b = band(alt(p), lo, hi);
            current.add(p);
            if (b != currentBand) {
                insertAt = addSegment(current, currentBand, insertAt);
                current = new ArrayList<>();
                current.add(p);
                currentBand = b;
            }
        }
        if (current.size() >= 2) {
            addSegment(current, currentBand, insertAt);
        }
    }

    public boolean isEmpty() {
        return segments.isEmpty();
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

    private int addSegment(List<GeoPoint> pts, int band, int insertAt) {
        Polyline line = new Polyline();
        line.getOutlinePaint().setColor(colorAt((band + 0.5f) / BANDS));
        line.getOutlinePaint().setStrokeWidth(strokeWidth);
        line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        line.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        line.setPoints(pts);
        mapView.getOverlays().add(insertAt, line);
        segments.add(line);
        return insertAt + 1;
    }

    private static double alt(GeoPoint p) {
        double a = p.getAltitude();
        return Double.isNaN(a) ? 0.0 : a;
    }

    private static int band(double alt, double lo, double hi) {
        double t = (alt - lo) / (hi - lo);
        int b = (int) Math.floor(t * BANDS);
        if (b < 0) b = 0;
        if (b >= BANDS) b = BANDS - 1;
        return b;
    }

    /** t = 0 → lowest (blue) … t = 1 → highest (red). */
    public static int colorAt(float t) {
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        float hue = 240f * (1f - t);
        return Color.HSVToColor(new float[]{hue, 0.95f, 0.9f});
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
}
