package nisargpatel.deadreckoning.sensor;

import android.hardware.SensorManager;
import android.location.Location;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.model.TurnEvent;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.stepcounting.DynamicStepCounter;
import nisargpatel.deadreckoning.stepcounting.StaticStepCounter;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;

public class DeadReckoningEngine {
    
    private final EnhancedStepCounter stepCounter;
    private final PreciseHeadingEstimator headingEstimator;
    private final GPSCalibrator gpsCalibrator;
    
    private double currentX = 0;
    private double currentY = 0;
    private double currentHeading = 0;
    private double previousHeading = 0;
    private double totalDistance = 0;
    
    private boolean isRunning = false;
    private Location startLocation = null;
    private Location lastGPSLocation = null;
    
    private Trip currentTrip = null;
    private final List<TurnEvent> turnEvents = new ArrayList<>();
    
    private static final double TURN_THRESHOLD = 25.0;
    private static final int MIN_STEPS_BETWEEN_TURNS = 5;
    private int stepsSinceLastTurn = 0;
    private double accumulatedHeadingChange = 0;
    private boolean isTurning = false;
    
    private int androidStepCount = 0;
    private int staticStepCount = 0;
    private StepCounterPreferences.StepMode stepMode = StepCounterPreferences.StepMode.DYNAMIC;
    
    private DynamicStepCounter dynamicStepCounter;
    private StaticStepCounter staticStepCounter;
    
    public DeadReckoningEngine() {
        stepCounter = new EnhancedStepCounter();
        headingEstimator = new PreciseHeadingEstimator();
        gpsCalibrator = new GPSCalibrator();
        
        dynamicStepCounter = new DynamicStepCounter(0.875);
        staticStepCounter = new StaticStepCounter(2, 1.9);
    }
    
    public void setStepMode(StepCounterPreferences.StepMode mode) {
        this.stepMode = mode;
    }
    
    public void start() {
        isRunning = true;
        stepCounter.reset();
        headingEstimator.reset();
        currentTrip = new Trip();
        turnEvents.clear();
        useManualHeading = false;
        headingOffset = 0;
        hardwareSteps = false;
        hardwareStepCount = 0;
        runStartT = -1;
        headingCount = 0;
        referencePressure = filteredPressure; // altitude 0 = where tracking started
        
        currentX = 0;
        currentY = 0;
        currentHeading = 0;
        previousHeading = 0;
        totalDistance = 0;
        accumulatedHeadingChange = 0;
        stepsSinceLastTurn = 0;
    }
    
    public void stop() {
        isRunning = false;
        
        if (currentTrip != null) {
            currentTrip.setTotalDistance(totalDistance);
            currentTrip.setTotalSteps(stepCounter.getStepCount());
            currentTrip.setTurnEvents(new ArrayList<>(turnEvents));
            currentTrip.finish();
        }
    }

    public void resetCalibration() {
        gpsCalibrator.resetCalibration();
    }
    
    public void reset() {
        currentX = 0;
        currentY = 0;
        currentHeading = 0;
        previousHeading = 0;
        totalDistance = 0;
        stepCounter.reset();
        headingEstimator.reset();
        gpsCalibrator.resetCalibration();
        
        currentTrip = null;
        turnEvents.clear();
        
        accumulatedHeadingChange = 0;
        stepsSinceLastTurn = 0;
        isTurning = false;
        referencePressure = Float.NaN;
        
        startLocation = null;
        lastGPSLocation = null;
    }
    
    public void updateSensors(float[] gravity, float[] magnetic, float[] gyro, float[] linearAccel, long timestamp) {
        if (!isRunning) return;
        
        if (gravity != null) {
            headingEstimator.updateGravity(gravity);
        }
        if (magnetic != null) {
            headingEstimator.updateMagneticField(magnetic);
        }
        if (gyro != null) {
            headingEstimator.updateGyroscope(gyro, timestamp);
        }
        
        if (!useOrientationTracker && gravity != null && magnetic != null) {
            previousHeading = currentHeading;
            if (!useManualHeading) {
                double base = useOrientationTracker
                        ? orientationTracker.getHeading()
                        : headingEstimator.getHeadingDegrees();
                currentHeading = normalize360(base + headingOffset);
            }
            detectTurn();
        }
        
        if (linearAccel != null) {
            double magnitude = Math.sqrt(
                linearAccel[0] * linearAccel[0] +
                linearAccel[1] * linearAccel[1] +
                linearAccel[2] * linearAccel[2]
            );
            
            boolean dynamicDetected = stepCounter.detectStep(linearAccel, timestamp);
            boolean staticDetected = staticStepCounter.findStep(magnitude);
            boolean detected = false;
            
            switch (stepMode) {
                case DYNAMIC:
                    detected = dynamicDetected;
                    break;
                case STATIC:
                    detected = staticDetected;
                    break;
                case ANDROID:
                    detected = false;
                    break;
            }
            
            if (detected && !hardwareSteps && !isHandlingPhone()) {
                updatePosition();
                stepsSinceLastTurn++;
            }
        }
    }
    
    public void addAndroidStep() {
        if (!isRunning) return;
        if (isHandlingPhone()) return;
        
        androidStepCount++;
        
        if (stepMode == StepCounterPreferences.StepMode.ANDROID) {
            updatePosition();
            stepsSinceLastTurn++;
        }
    }
    
    private void detectTurn() {
        double headingDelta = currentHeading - previousHeading;
        
        if (Math.abs(headingDelta) > 180) {
            if (headingDelta > 0) {
                headingDelta -= 360;
            } else {
                headingDelta += 360;
            }
        }
        
        if (Math.abs(headingDelta) > 5 && !isTurning) {
            isTurning = true;
            accumulatedHeadingChange = 0;
        }
        
        if (isTurning) {
            accumulatedHeadingChange += headingDelta;
            
            if (Math.abs(accumulatedHeadingChange) >= TURN_THRESHOLD && stepsSinceLastTurn >= MIN_STEPS_BETWEEN_TURNS) {
                TurnEvent.TurnType turnType = TurnEvent.determineTurnType(accumulatedHeadingChange);
                
                if (turnType != null && Math.abs(accumulatedHeadingChange) < 270) {
                    addTurnEvent(turnType, accumulatedHeadingChange);
                }
                
                isTurning = false;
                accumulatedHeadingChange = 0;
                stepsSinceLastTurn = 0;
            } else if (Math.abs(accumulatedHeadingChange) < 5) {
                isTurning = false;
                accumulatedHeadingChange = 0;
            }
        }
    }
    
    private void addTurnEvent(TurnEvent.TurnType type, double headingChange) {
        double lat = 0, lon = 0;
        
        if (currentTrip != null && !currentTrip.getPathPoints().isEmpty()) {
            GeoPoint lastPoint = currentTrip.getPathPoints().get(currentTrip.getPathPoints().size() - 1);
            lat = lastPoint.getLatitude();
            lon = lastPoint.getLongitude();
        }
        
        TurnEvent event = new TurnEvent(type, headingChange, stepCounter.getStepCount(), lat, lon);
        turnEvents.add(event);
        
        if (currentTrip != null) {
            currentTrip.addTurnEvent(event);
        }
    }
    
    private void updatePosition() {
        double strideLength = stepCounter.getStrideLength();
        // Heading for this step: smoothed over the last ~1.2 s so the sway of the
        // phone during each stride (strong in a pocket) does not zig-zag the track.
        double correctedHeading = (useOrientationTracker && !useManualHeading)
                ? getStepHeading()
                : currentHeading;
        double headingRadians = Math.toRadians(correctedHeading);
        
        double deltaX = strideLength * Math.sin(headingRadians);
        double deltaY = strideLength * Math.cos(headingRadians);
        
        currentX += deltaX;
        currentY += deltaY;
        totalDistance += strideLength;
        
        if (currentTrip != null) {
            GeoPoint point = calculateGeoPoint(currentX, currentY);
            if (point != null) {
                if (hasAltitude()) {
                    point.setAltitude(getRelativeAltitude());
                }
                currentTrip.addPoint(point);
            }
        }
    }
    
    private GeoPoint calculateGeoPoint(double x, double y) {
        if (startLocation == null) return null;
        
        double earthRadius = 6371000; // meters
        double latRad = Math.toRadians(startLocation.getLatitude());
        double lonRad = Math.toRadians(startLocation.getLongitude());
        
        double newLatRad = latRad + y / earthRadius;
        double newLonRad = lonRad + x / (earthRadius * Math.cos(latRad));
        
        return new GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad));
    }
    
    public void calibrateWithGPS(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 20) {
            return;
        }
        
        if (startLocation == null) {
            startLocation = location;
            lastGPSLocation = location;
            
            if (currentTrip != null) {
                GeoPoint startPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
                if (hasAltitude()) {
                    startPoint.setAltitude(getRelativeAltitude());
                }
                currentTrip.addPoint(startPoint);
            }
            return;
        }
        
        // With a good GPS fix the position simply follows GPS. (The old calibrator rescaled
        // the whole position on every fix and learned a heading bias from noisy 1-second GPS
        // bearings, which distorted and rotated the track - it is no longer applied.)
        double lat0 = Math.toRadians(startLocation.getLatitude());
        double earthRadius = 6371000;
        currentY = Math.toRadians(location.getLatitude() - startLocation.getLatitude()) * earthRadius;
        currentX = Math.toRadians(location.getLongitude() - startLocation.getLongitude()) * earthRadius * Math.cos(lat0);
        
        lastGPSLocation = location;
    }
    
    public double getX() {
        return currentX;
    }
    
    public double getY() {
        return currentY;
    }
    
    public double getHeading() {
        return currentHeading;
    }

    // Recent headings for step smoothing (circular mean).
    private static final int HEADING_HIST = 128;
    private static final long STEP_HEADING_WINDOW_NS = 1_200_000_000L;
    private final long[] headingT = new long[HEADING_HIST];
    private final double[] headingSin = new double[HEADING_HIST];
    private final double[] headingCos = new double[HEADING_HIST];
    private int headingPos = 0;
    private int headingCount = 0;
    private long lastHeadingT = 0;

    private void recordHeading(long t, double deg) {
        double r = Math.toRadians(deg);
        headingT[headingPos] = t;
        headingSin[headingPos] = Math.sin(r);
        headingCos[headingPos] = Math.cos(r);
        headingPos = (headingPos + 1) % HEADING_HIST;
        if (headingCount < HEADING_HIST) headingCount++;
        lastHeadingT = t;
    }

    /** Walking direction for the current step: circular mean of the last ~1.2 s. */
    public double getStepHeading() {
        if (headingCount == 0) return currentHeading;
        double sx = 0;
        double cx = 0;
        int used = 0;
        for (int i = 0; i < headingCount; i++) {
            if (lastHeadingT - headingT[i] <= STEP_HEADING_WINDOW_NS) {
                sx += headingSin[i];
                cx += headingCos[i];
                used++;
            }
        }
        if (used == 0 || (Math.abs(sx) < 1e-9 && Math.abs(cx) < 1e-9)) return currentHeading;
        return normalize360(Math.toDegrees(Math.atan2(sx, cx)));
    }
    
    public int getStepCount() {
        if (hardwareSteps) {
            return hardwareStepCount;
        }
        switch (stepMode) {
            case DYNAMIC:
                return stepCounter.getStepCount();
            case STATIC:
                return staticStepCounter.getStepCount();
            case ANDROID:
                return androidStepCount;
            default:
                return stepCounter.getStepCount();
        }
    }
    
    public int getDynamicStepCount() {
        return stepCounter.getStepCount();
    }
    
    public int getStaticStepCount() {
        return staticStepCounter.getStepCount();
    }
    
    public int getAndroidStepCount() {
        return androidStepCount;
    }
    
    public double getDistance() {
        return totalDistance;
    }
    
    public double getDistanceFromGPS(Location gpsLocation) {
        if (startLocation == null || gpsLocation == null) {
            return -1;
        }
        return startLocation.distanceTo(gpsLocation);
    }
    
    public boolean isCalibrated() {
        return gpsCalibrator.isCalibrated();
    }
    
    public int getCalibrationPoints() {
        return gpsCalibrator.getCalibrationPointCount();
    }
    
    public void setStrideLength(double meters) {
        stepCounter.setStrideLength(meters);
    }
    
    public double getStrideLength() {
        return stepCounter.getStrideLength();
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public Location getStartLocation() {
        return startLocation;
    }
    
    public Location getLastGPSLocation() {
        return lastGPSLocation;
    }
    
    public double getScaleFactor() {
        return gpsCalibrator.getScaleFactor();
    }
    
    public Trip getCurrentTrip() {
        return currentTrip;
    }
    
    public List<TurnEvent> getTurnEvents() {
        return new ArrayList<>(turnEvents);
    }
    
    public boolean isTurning() {
        return isTurning;
    }
    
    public double getAccumulatedHeadingChange() {
        return accumulatedHeadingChange;
    }

    private static final double MANUAL_TURN_ANGLE = 90.0;
    private boolean useManualHeading = false;
    private double manualHeadingOffset = 0;

    public void turnLeft() {
        if (!isRunning) return;
        
        if (compassFollow) {
            setFixedHeading(getHeading() + (-MANUAL_TURN_ANGLE));
            addManualTurnEvent(TurnEvent.TurnType.LEFT, -MANUAL_TURN_ANGLE);
            return;
        }

        useManualHeading = true;
        currentHeading -= MANUAL_TURN_ANGLE;
        
        while (currentHeading < 0) {
            currentHeading += 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.LEFT, -MANUAL_TURN_ANGLE);
    }

    public void turnRight() {
        if (!isRunning) return;
        
        if (compassFollow) {
            setFixedHeading(getHeading() + (MANUAL_TURN_ANGLE));
            addManualTurnEvent(TurnEvent.TurnType.RIGHT, MANUAL_TURN_ANGLE);
            return;
        }

        useManualHeading = true;
        currentHeading += MANUAL_TURN_ANGLE;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.RIGHT, MANUAL_TURN_ANGLE);
    }

    public void turnAround() {
        if (!isRunning) return;
        
        if (compassFollow) {
            setFixedHeading(getHeading() + 180);
            addManualTurnEvent(TurnEvent.TurnType.UTURN, 180);
            return;
        }

        useManualHeading = true;
        currentHeading += 180;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.UTURN, 180);
    }

    private void addManualTurnEvent(TurnEvent.TurnType type, double angle) {
        double lat = 0, lon = 0;
        
        if (currentTrip != null && !currentTrip.getPathPoints().isEmpty()) {
            GeoPoint lastPoint = currentTrip.getPathPoints().get(currentTrip.getPathPoints().size() - 1);
            lat = lastPoint.getLatitude();
            lon = lastPoint.getLongitude();
        }
        
        TurnEvent event = new TurnEvent(type, angle, stepCounter.getStepCount(), lat, lon);
        turnEvents.add(event);
        
        if (currentTrip != null) {
            currentTrip.addTurnEvent(event);
        }
    }
    
    // Android's own step detector (TYPE_STEP_DETECTOR): reliable in hand and in a pocket.
    // Once it reports a step, it replaces the accelerometer-based detection.
    private boolean hardwareSteps = false;
    private int hardwareStepCount = 0;

    public void onHardwareStep() {
        if (!isRunning) return;
        hardwareSteps = true;
        hardwareStepCount++;
        if (isHandlingPhone()) return;
        updatePosition();
        stepsSinceLastTurn++;
    }

    // Fused orientation (TYPE_ROTATION_VECTOR) with carry-position change handling.
    private final OrientationTracker orientationTracker = new OrientationTracker();
    private boolean useOrientationTracker = false;

    /** Feed TYPE_ROTATION_VECTOR samples. Once data arrives it replaces the old estimator. */
    public void updateRotationVector(float[] values, long timestamp) {
        orientationTracker.update(values, timestamp);
        useOrientationTracker = orientationTracker.hasData();
        if (isRunning && !useManualHeading) {
            previousHeading = currentHeading;
            currentHeading = normalize360(orientationTracker.getHeading() + headingOffset);
            if (!orientationTracker.isHandling()) {
                recordHeading(timestamp, currentHeading);
            }
            detectTurn();
        }
        if (isRunning && runStartT < 0) {
            runStartT = timestamp;
        }
    }

    // North alignment: the gyro-based game rotation vector gives smooth, magnet-immune turns,
    // the magnetic rotation vector gives true north. North is taken from the magnetometer only
    // before tracking and during the first seconds of it, then frozen, so steel, rails and
    // buildings can no longer bend the track.
    private static final long NORTH_ALIGN_NS = 5_000_000_000L;
    private long runStartT = -1;

    public void updateMagneticRotationVector(float[] values, long timestamp) {
        boolean allow = !isRunning || runStartT < 0 || timestamp - runStartT < NORTH_ALIGN_NS;
        orientationTracker.updateNorthReference(values, allow);
    }

    /** True while the phone is being moved to a new carry position (steps are ignored). */
    public boolean isHandlingPhone() {
        return useOrientationTracker && orientationTracker.isHandling();
    }

    /**
     * Keep the current walking direction after the phone has been moved
     * (e.g. when tracking resumes after a pause).
     */
    public void holdHeadingAcrossReposition() {
        if (useOrientationTracker) {
            orientationTracker.requestReanchor(normalize360(currentHeading - headingOffset));
        }
    }

    /** Used when tracking without any GPS fix: anchor the track at a chosen point. */
    public void ensureStartLocation(double lat, double lon) {
        if (startLocation != null) return;
        Location l = new Location("manual");
        l.setLatitude(lat);
        l.setLongitude(lon);
        startLocation = l;
        lastGPSLocation = l;
        currentX = 0;
        currentY = 0;
        if (currentTrip != null) {
            GeoPoint p = new GeoPoint(lat, lon);
            if (hasAltitude()) {
                p.setAltitude(getRelativeAltitude());
            }
            currentTrip.addPoint(p);
        }
    }

    // Barometric altimeter: altitude relative to the point where tracking started.
    private static final float PRESSURE_SMOOTHING = 0.1f;
    private float filteredPressure = Float.NaN;
    private float referencePressure = Float.NaN;

    public void updatePressure(float hPa) {
        if (hPa <= 0) return;
        if (Float.isNaN(filteredPressure)) {
            filteredPressure = hPa;
        } else {
            filteredPressure += PRESSURE_SMOOTHING * (hPa - filteredPressure);
        }
        if (isRunning && Float.isNaN(referencePressure)) {
            referencePressure = filteredPressure;
        }
    }

    public boolean hasAltitude() {
        return !Float.isNaN(referencePressure) && !Float.isNaN(filteredPressure);
    }

    /** Metres above (+) or below (-) the start point. */
    public double getRelativeAltitude() {
        if (!hasAltitude()) return 0.0;
        return SensorManager.getAltitude(referencePressure, filteredPressure);
    }

    // Compass-follow mode: heading keeps tracking the compass/gyro.
    // setFixedHeading() and the turn buttons only shift a correction offset.
    private boolean compassFollow = true;
    private double headingOffset = 0;

    public void setCompassFollow(boolean follow) {
        compassFollow = follow;
        if (follow) {
            useManualHeading = false;
        }
    }

    public boolean isCompassFollow() {
        return compassFollow;
    }

    private static double normalize360(double deg) {
        double d = deg % 360.0;
        if (d < 0) d += 360.0;
        return d;
    }

    public void setFixedHeading(double heading) {
        if (compassFollow) {
            double delta = heading - getHeading();
            headingOffset = normalize360(headingOffset + delta);
            currentHeading = normalize360(currentHeading + delta);
            previousHeading = currentHeading;
            useManualHeading = false;
            return;
        }
        currentHeading = heading;
        useManualHeading = true;
    }
    
    public void resetToCompassHeading() {
        useManualHeading = false;
    }
}
