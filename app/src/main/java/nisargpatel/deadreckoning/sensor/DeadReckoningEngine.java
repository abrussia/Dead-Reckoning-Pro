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
            
            if (detected && !isHandlingPhone()) {
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
        double correctedHeading = gpsCalibrator.correctHeading(currentHeading);
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
        
        gpsCalibrator.addCalibrationPoint(
            location,
            currentX,
            currentY
        );
        
        double[] corrected = gpsCalibrator.correctPosition(currentX, currentY, currentHeading);
        currentX = corrected[0];
        currentY = corrected[1];
        
        lastGPSLocation = location;
    }
    
    public double getX() {
        return currentX;
    }
    
    public double getY() {
        return currentY;
    }
    
    public double getHeading() {
        return gpsCalibrator.correctHeading(currentHeading);
    }
    
    public int getStepCount() {
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
            detectTurn();
        }
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
