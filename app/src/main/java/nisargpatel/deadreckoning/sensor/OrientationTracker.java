package nisargpatel.deadreckoning.sensor;

import android.hardware.SensorManager;

/**
 * Walking-direction estimator built on Android's fused TYPE_ROTATION_VECTOR
 * (gyro + magnetometer + accelerometer fused by the OS).
 *
 * Works in any carry position:
 *  - phone held flat-ish: heading = direction the top of the phone points;
 *  - phone upright (pocket): heading = direction the back of the phone points.
 *
 * When the phone is re-positioned (taken out of / put into a pocket, turned in the hand)
 * a "handling" phase is detected. While handling, steps should be ignored and the heading
 * is held. When the phone settles in its new position, the heading is re-anchored so that
 * the walking direction stays the same as before the phone was moved.
 */
public class OrientationTracker {

    // An orientation change larger than this (relative to the settled position) means handling.
    private static final double HANDLING_ANGLE_DEG = 40.0;
    // ...and it must last at least this long (filters out single jolts / stumbles).
    private static final long HANDLING_CONFIRM_NS = 300_000_000L;
    // The phone counts as settled when it stays within this angle of its average orientation...
    private static final double SETTLED_ANGLE_DEG = 20.0;
    // ...for this long.
    private static final long SETTLE_TIME_NS = 1_000_000_000L;
    // Heading used for re-anchoring is taken from this long before handling was confirmed.
    private static final long LOOKBACK_NS = 800_000_000L;
    // An axis whose vertical component exceeds this is too steep to give a heading.
    private static final double AXIS_STEEP = 0.75;

    private static final int HISTORY = 128;

    private final float[] rotation = new float[9];
    private final float[] magRotation = new float[9];

    // Offset from the (gyro-based) rotation frame to magnetic north.
    private double northOffset = 0;
    private double northSin = 0;
    private double northCos = 1;
    private boolean northInit = false;

    private boolean hasData = false;
    private boolean useBackAxis = false;  // false: device Y axis (top); true: device -Z axis (back)
    private double carryOffset = 0;       // added to the raw axis azimuth
    private double heading = 0;           // output, 0..360

    // Settled orientation: "up" expressed in device coordinates.
    private final double[] refUp = new double[3];
    private boolean hasRef = false;

    // Smoothed "up" (device coords) for settle detection.
    private final double[] avgUp = new double[3];
    private boolean hasAvg = false;

    private boolean handling = false;
    private long deviationSince = -1;
    private long settledSince = -1;
    private double heldHeading = 0;

    private boolean reanchorRequested = false;
    private double reanchorTarget = 0;

    private final long[] histT = new long[HISTORY];
    private final double[] histH = new double[HISTORY];
    private int histPos = 0;
    private int histCount = 0;

    public boolean hasData() {
        return hasData;
    }

    public boolean isHandling() {
        return handling;
    }

    /** Heading of walking direction in degrees, 0..360 (0 = magnetic north). */
    public double getHeading() {
        return heading;
    }

    /** On the next sample, re-anchor so that the heading equals {@code target}. */
    public void requestReanchor(double target) {
        reanchorRequested = true;
        reanchorTarget = target;
    }

    public void reset() {
        hasData = false;
        hasRef = false;
        hasAvg = false;
        handling = false;
        deviationSince = -1;
        settledSince = -1;
        carryOffset = 0;
        heading = 0;
        histCount = 0;
        histPos = 0;
        reanchorRequested = false;
    }

    public void update(float[] rotationVector, long timestamp) {
        SensorManager.getRotationMatrixFromVector(rotation, trim(rotationVector));

        // World "up" in device coordinates is the third row of the matrix.
        double[] up = {rotation[6], rotation[7], rotation[8]};

        if (!hasData) {
            hasData = true;
            anchorTo(up, Double.NaN);
            pushHistory(timestamp, heading);
            return;
        }

        updateAverage(up);

        if (reanchorRequested) {
            reanchorRequested = false;
            handling = false;
            deviationSince = -1;
            anchorTo(up, reanchorTarget);
            pushHistory(timestamp, heading);
            return;
        }

        if (!handling) {
            double dev = angleDeg(up, refUp);
            if (dev > HANDLING_ANGLE_DEG) {
                if (deviationSince < 0) deviationSince = timestamp;
                if (timestamp - deviationSince >= HANDLING_CONFIRM_NS) {
                    handling = true;
                    heldHeading = headingAt(timestamp - LOOKBACK_NS);
                    heading = heldHeading;
                    settledSince = -1;
                    return;
                }
            } else {
                deviationSince = -1;
            }
            heading = normalize(rawAzimuth() + carryOffset + northOffset);
            pushHistory(timestamp, heading);
        } else {
            // Holding the heading until the phone settles in its new position.
            double wobble = angleDeg(up, avgUp);
            if (wobble < SETTLED_ANGLE_DEG) {
                if (settledSince < 0) settledSince = timestamp;
                if (timestamp - settledSince >= SETTLE_TIME_NS) {
                    handling = false;
                    deviationSince = -1;
                    anchorTo(up, heldHeading);
                    pushHistory(timestamp, heading);
                }
            } else {
                settledSince = -1;
            }
        }
    }

    /**
     * Take the current orientation as the settled one. If {@code targetHeading} is a number,
     * choose the offset so the output equals it; otherwise keep the raw azimuth (offset 0).
     */
    private void anchorTo(double[] up, double targetHeading) {
        refUp[0] = up[0];
        refUp[1] = up[1];
        refUp[2] = up[2];
        hasRef = true;

        // Device Y axis in world coords is column 1; its vertical part is rotation[7].
        useBackAxis = Math.abs(rotation[7]) > AXIS_STEEP;

        double raw = rawAzimuth();
        if (Double.isNaN(targetHeading)) {
            carryOffset = 0;
            heading = normalize(raw + northOffset);
        } else {
            carryOffset = normalize(targetHeading - raw - northOffset);
            heading = normalize(targetHeading);
        }
    }

    /** Azimuth of the chosen device axis projected onto the horizontal plane. */
    private double rawAzimuth() {
        double a = axisAzimuth(rotation, useBackAxis);
        return Double.isNaN(a) ? heading - carryOffset - northOffset : a;
    }

    private static double axisAzimuth(float[] r, boolean back) {
        double east;
        double north;
        if (back) {
            // Back of the phone = -Z axis (column 2, negated).
            east = -r[2];
            north = -r[5];
        } else {
            // Top of the phone = +Y axis (column 1).
            east = r[1];
            north = r[4];
        }
        if (Math.abs(east) < 1e-6 && Math.abs(north) < 1e-6) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan2(east, north));
    }

    /**
     * Magnetic rotation vector (TYPE_ROTATION_VECTOR) sample. While {@code allow} is true it
     * slowly aligns the gyro-based frame to magnetic north; otherwise it is ignored.
     */
    public void updateNorthReference(float[] magneticRotationVector, boolean allow) {
        if (!allow || !hasData || handling) return;
        SensorManager.getRotationMatrixFromVector(magRotation, trim(magneticRotationVector));
        double magAz = axisAzimuth(magRotation, useBackAxis);
        double gyroAz = axisAzimuth(rotation, useBackAxis);
        if (Double.isNaN(magAz) || Double.isNaN(gyroAz)) return;
        double d = Math.toRadians(magAz - gyroAz);
        if (!northInit) {
            northSin = Math.sin(d);
            northCos = Math.cos(d);
            northInit = true;
        } else {
            northSin += 0.05 * (Math.sin(d) - northSin);
            northCos += 0.05 * (Math.cos(d) - northCos);
        }
        double newOffset = Math.toDegrees(Math.atan2(northSin, northCos));
        heading = normalize(heading + (newOffset - northOffset));
        northOffset = newOffset;
    }

    private static float[] trim(float[] rv) {
        if (rv.length > 4) {
            // Some devices send 5 values; getRotationMatrixFromVector can choke on them.
            return new float[]{rv[0], rv[1], rv[2], rv[3]};
        }
        return rv;
    }

    private void updateAverage(double[] up) {
        if (!hasAvg) {
            avgUp[0] = up[0];
            avgUp[1] = up[1];
            avgUp[2] = up[2];
            hasAvg = true;
            return;
        }
        double a = 0.05;
        for (int i = 0; i < 3; i++) {
            avgUp[i] += a * (up[i] - avgUp[i]);
        }
    }

    private void pushHistory(long t, double h) {
        histT[histPos] = t;
        histH[histPos] = h;
        histPos = (histPos + 1) % HISTORY;
        if (histCount < HISTORY) histCount++;
    }

    /** Most recent recorded heading at or before time t (oldest available if none). */
    private double headingAt(long t) {
        if (histCount == 0) return heading;
        double best = heading;
        long bestT = Long.MIN_VALUE;
        double oldest = heading;
        long oldestT = Long.MAX_VALUE;
        for (int i = 0; i < histCount; i++) {
            long ht = histT[i];
            if (ht <= t && ht > bestT) {
                bestT = ht;
                best = histH[i];
            }
            if (ht < oldestT) {
                oldestT = ht;
                oldest = histH[i];
            }
        }
        return bestT == Long.MIN_VALUE ? oldest : best;
    }

    private static double angleDeg(double[] a, double[] b) {
        double na = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double nb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (na < 1e-9 || nb < 1e-9) return 0;
        double c = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb);
        if (c > 1) c = 1;
        if (c < -1) c = -1;
        return Math.toDegrees(Math.acos(c));
    }

    private static double normalize(double d) {
        double r = d % 360.0;
        if (r < 0) r += 360.0;
        return r;
    }
}
