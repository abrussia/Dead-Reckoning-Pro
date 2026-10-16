package nisargpatel.deadreckoning.sensor;

import java.util.ArrayList;
import java.util.List;

public class EnhancedStepCounter {
    
    private static final int WINDOW_SIZE = 10;
    // Input is LINEAR acceleration (gravity removed), so walking peaks are ~1.5-5 m/s^2.
    // The old values (9.5 / 11.5) were meant for total acceleration and almost never fired.
    private static final double STEP_THRESHOLD_LOW = 0.6;
    private static final double STEP_THRESHOLD_HIGH = 2.0;
    // Sensor timestamps are in nanoseconds.
    private static final long STEP_MIN_TIME_NS = 300_000_000L;
    
    private List<Double> accelerationWindow = new ArrayList<>();
    private List<Long> timestampWindow = new ArrayList<>();
    
    private int stepCount = 0;
    private long lastStepTime = 0;
    private boolean isPeakFound = false;
    private double peakValue = 0;
    private double currentStrideLength = 0.75;
    
    private KalmanFilter kalmanFilter;
    private double lastAcceleration = 0;
    
    public EnhancedStepCounter() {
        kalmanFilter = new KalmanFilter(0.01, 0.1, 0, 1);
    }
    
    // Adaptive detector on linear-acceleration magnitude:
    // light smoothing, a slow running mean as baseline, and a step is counted when the
    // signal rises clearly above the baseline and then falls back to it.
    private static final double SMOOTH_ALPHA = 0.35;     // fast EMA (noise)
    private static final double BASELINE_ALPHA = 0.02;  // slow EMA (~1 s at 50 Hz)
    private static final double MIN_RISE = 0.4;          // m/s^2 above baseline to arm
    private double smooth = 0;
    private double baseline = 0;
    private boolean initialised = false;

    public boolean detectStep(float[] linearAcceleration, long timestamp) {
        double magnitude = Math.sqrt(
            linearAcceleration[0] * linearAcceleration[0] +
            linearAcceleration[1] * linearAcceleration[1] +
            linearAcceleration[2] * linearAcceleration[2]
        );
        if (!initialised) {
            smooth = magnitude;
            baseline = magnitude;
            initialised = true;
            return false;
        }
        smooth += SMOOTH_ALPHA * (magnitude - smooth);
        baseline += BASELINE_ALPHA * (smooth - baseline);
        return analyzeForStep(smooth, timestamp);
    }

    private boolean analyzeForStep(double acceleration, long timestamp) {
        double rise = acceleration - baseline;
        if (!isPeakFound) {
            if (rise > MIN_RISE && acceleration > STEP_THRESHOLD_LOW) {
                isPeakFound = true;
                peakValue = acceleration;
            }
            return false;
        }
        if (acceleration > peakValue) {
            peakValue = acceleration;
        }
        if (acceleration < baseline) {
            isPeakFound = false;
            if (timestamp - lastStepTime > STEP_MIN_TIME_NS) {
                lastStepTime = timestamp;
                stepCount++;
                return true;
            }
        }
        return false;
    }

    public void setStrideLength(double strideLengthMeters) {
        this.currentStrideLength = strideLengthMeters;
    }
    
    public double getStrideLength() {
        return currentStrideLength;
    }
    
    public int getStepCount() {
        return stepCount;
    }
    
    public void reset() {
        stepCount = 0;
        lastStepTime = 0;
        isPeakFound = false;
        accelerationWindow.clear();
        timestampWindow.clear();
        kalmanFilter.reset(0, 1);
        initialised = false;
    }
    
    public double getDistanceTraveled() {
        return stepCount * currentStrideLength;
    }
    
    public double getCurrentAcceleration() {
        if (accelerationWindow.isEmpty()) return 0;
        return accelerationWindow.get(accelerationWindow.size() - 1);
    }
    
    public double getFilteredAcceleration() {
        return kalmanFilter.getEstimate();
    }
    
    public List<Double> getAccelerationHistory() {
        return new ArrayList<>(accelerationWindow);
    }
}
