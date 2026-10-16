package nisargpatel.deadreckoning.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.osmdroid.api.IMapController;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.model.Marker;
import nisargpatel.deadreckoning.preferences.TurnModePreferences;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.sensor.DeadReckoningEngine;
import nisargpatel.deadreckoning.sensor.PreciseHeadingEstimator;
import nisargpatel.deadreckoning.storage.MarkerStorage;
import nisargpatel.deadreckoning.storage.TripStorage;
import nisargpatel.deadreckoning.view.DegreeDialView;

public class MapFragment extends Fragment implements SensorEventListener {

    private MapView mapView;
    private MyLocationNewOverlay locationOverlay;
    private Polyline pathOverlay;
    
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    private SensorManager sensorManager;
    private Sensor sensorGravity;
    private Sensor sensorMagnetic;
    private Sensor sensorGyroscope;
    private Sensor sensorLinearAcceleration;
    private Sensor sensorPressure;
    private nisargpatel.deadreckoning.view.AltitudeTrack altitudeTrack;

    private DeadReckoningEngine deadReckoningEngine;
    private PreciseHeadingEstimator headingEstimator;
    private TripStorage tripStorage;
    private MarkerStorage markerStorage;
    private TurnModePreferences turnModePrefs;
    private StepCounterPreferences stepPrefs;

    private TextView textSteps;
    private TextView textDistance;
    private TextView textHeading;
    private TextView textGPSAccuracy;
    private TextView textGPSStatus;
    private TextView textTurnIndicator;
    private MaterialButton buttonModeToggle;
    private MaterialButton buttonGPSCalibrate;
    private MaterialCardView cardTurn;
    private LinearLayout manualTurnControls;

    private FloatingActionButton buttonTurnLeft;
    private FloatingActionButton buttonTurnRight;
    private FloatingActionButton buttonTurnAround;
    private FloatingActionButton buttonOpenDial;
    private FloatingActionButton fabCenterGPS;
    private FloatingActionButton fabAddMarker;
    private FloatingActionButton fabClearAll;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton buttonStartStop;
    private MaterialButton buttonPause;
    private MaterialButton buttonNoGPS;
    private MaterialButton buttonConfirmDial;
    private ImageView imageDirectionArrow;
    private MaterialCardView cardDirection;
    private FrameLayout dialOverlayContainer;
    private DegreeDialView degreeDialView;

    private List<GeoPoint> pathPoints = new ArrayList<>();
    private List<GeoPoint> noGPSPathPoints = new ArrayList<>();
    private List<GeoPoint> recentGPSPoints = new ArrayList<>();
    private List<Marker> mapMarkers = new ArrayList<>();
    private List<org.osmdroid.views.overlay.Marker> markerOverlays = new ArrayList<>();
    private GeoPoint currentPosition = null;
    private GeoPoint startPosition = null;
    private GeoPoint lastGPSPoint = null;

    private boolean isTracking = false;
    private boolean isPaused = false;
    private boolean isManualMode = false;
    private Location lastKnownGPS = null;
    private boolean useGPSForTrace = false;
    private boolean forceNoGPSMode = false;
    private long lastGPSMoveTime = 0;
    private GeoPoint lastDeadReckoningPoint = null;
    private double lastDeadReckoningDistance = 0;
    private double noGPSFixedHeading = 0;
    private Polyline noGPSPathOverlay;
    private boolean isAddingMarker = false;
    private String selectedEmoji = "📍";
    private GeoPoint pendingMarkerPosition = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        turnModePrefs = new TurnModePreferences(requireContext());
        stepPrefs = new StepCounterPreferences(requireContext());
        markerStorage = new MarkerStorage(requireContext());
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;

        initViews(view);
        initSensors();
        initLocation();
        initMap(view);
        initEngine();
        updateTurnModeUI();
        loadMarkers();
    }

    private void initViews(View view) {
        textSteps = view.findViewById(R.id.textSteps);
        textDistance = view.findViewById(R.id.textDistance);
        textHeading = view.findViewById(R.id.textHeading);
        textGPSAccuracy = view.findViewById(R.id.textGPSAccuracy);
        textGPSStatus = view.findViewById(R.id.textGPSStatus);
        textTurnIndicator = view.findViewById(R.id.textTurnIndicator);
        buttonModeToggle = view.findViewById(R.id.buttonModeToggle);
        buttonGPSCalibrate = view.findViewById(R.id.buttonGPSCalibrate);
        cardTurn = view.findViewById(R.id.cardTurn);
        manualTurnControls = view.findViewById(R.id.manualTurnControls);

        buttonTurnLeft = view.findViewById(R.id.buttonTurnLeft);
        buttonTurnRight = view.findViewById(R.id.buttonTurnRight);
        buttonTurnAround = view.findViewById(R.id.buttonTurnAround);
        fabCenterGPS = view.findViewById(R.id.fabCenterGPS);
        fabAddMarker = view.findViewById(R.id.fabAddMarker);
        fabClearAll = view.findViewById(R.id.fabClearAll);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPause = view.findViewById(R.id.buttonPause);
        buttonNoGPS = view.findViewById(R.id.buttonNoGPS);
        imageDirectionArrow = view.findViewById(R.id.imageDirectionArrow);
        cardDirection = view.findViewById(R.id.cardDirection);

        buttonOpenDial = view.findViewById(R.id.buttonOpenDial);
        dialOverlayContainer = view.findViewById(R.id.dialOverlayContainer);
        degreeDialView = view.findViewById(R.id.degreeDialView);
        buttonConfirmDial = view.findViewById(R.id.buttonConfirmDial);

        buttonModeToggle.setOnClickListener(v -> toggleTurnMode());
        
        if (buttonNoGPS != null) {
            buttonNoGPS.setOnClickListener(v -> toggleNoGPSMode());
        }
        
        buttonGPSCalibrate.setOnClickListener(v -> calibrateGPS());

        buttonOpenDial.setOnClickListener(v -> openDialOverlay());
        
        dialOverlayContainer.setOnClickListener(v -> closeDialOverlay());
        
        degreeDialView.setOnDegreeChangedListener(degree -> {
            textHeading.setText(String.format("%.0f°", degree));
        });
        
        buttonConfirmDial.setOnClickListener(v -> confirmDialSelection());
        
        buttonTurnLeft.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnLeft();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("Left ↰");
            }
        });
        
        buttonTurnRight.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnRight();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("Right ↱");
            }
        });
        
        buttonTurnAround.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnAround();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("U-Turn 🔄");
            }
        });

        fabCenterGPS.setOnClickListener(v -> centerOnGPS());
        
        fabAddMarker.setOnClickListener(v -> toggleAddMarkerMode());
        
        fabClearAll.setOnClickListener(v -> showClearAllDialog());
        
        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
                if (isTracking) {
                    stopTracking();
                } else {
                    startTracking();
                }
            });
        }
        
        if (buttonPause != null) {
            buttonPause.setOnClickListener(v -> togglePause());
        }
    }
    
    private void togglePause() {
        if (!isTracking) return;
        
        isPaused = !isPaused;
        
        if (isPaused) {
            buttonPause.setText(R.string.resume);
            buttonPause.setIconResource(R.drawable.ic_play);
            Toast.makeText(requireContext(), "Tracking paused", Toast.LENGTH_SHORT).show();
        } else {
            buttonPause.setText(R.string.pause);
            buttonPause.setIconResource(R.drawable.ic_pause);
            Toast.makeText(requireContext(), "Tracking resumed", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleNoGPSMode() {
        forceNoGPSMode = !forceNoGPSMode;
        useGPSForTrace = !forceNoGPSMode;
        
        if (forceNoGPSMode) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
            manualTurnControls.setVisibility(View.VISIBLE);
            
            GeoPoint startPoint = calculatePoint5MetersBack();
            
            if (startPoint != null) {
                lastDeadReckoningPoint = startPoint;
            } else if (lastKnownGPS != null && currentPosition != null) {
                lastDeadReckoningPoint = currentPosition;
            }

            if (lastDeadReckoningPoint == null && mapView != null) {
                // No GPS fix at all: start the track at the centre of the map
                org.osmdroid.api.IGeoPoint centre = mapView.getMapCenter();
                lastDeadReckoningPoint = new GeoPoint(centre.getLatitude(), centre.getLongitude());
                currentPosition = lastDeadReckoningPoint;
                Toast.makeText(requireContext(), "No GPS fix: track starts at the map centre", Toast.LENGTH_LONG).show();
            }

            if (lastDeadReckoningPoint != null) {
                if (deadReckoningEngine != null) {
                    deadReckoningEngine.ensureStartLocation(
                            lastDeadReckoningPoint.getLatitude(), lastDeadReckoningPoint.getLongitude());
                }
                if (noGPSPathPoints.isEmpty()) {
                    GeoPoint first = new GeoPoint(lastDeadReckoningPoint.getLatitude(), lastDeadReckoningPoint.getLongitude());
                    if (deadReckoningEngine != null && deadReckoningEngine.hasAltitude()) {
                        first.setAltitude(deadReckoningEngine.getRelativeAltitude());
                    }
                    noGPSPathPoints.add(first);
                }
            }

            // Really stop using GPS: no location updates, no GPS dot, no map following it
            setGpsActive(false);
            updateDeadReckoningMarker(lastDeadReckoningPoint);
            
            if (deadReckoningEngine != null) {
                deadReckoningEngine.setCompassFollow(!isManualMode);
                lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                deadReckoningEngine.setFixedHeading(noGPSFixedHeading);
            }
            
            Toast.makeText(requireContext(), isManualMode
                    ? "No GPS: manual - use arrows/dial to turn"
                    : "No GPS: compass - turns are tracked automatically", Toast.LENGTH_SHORT).show();
        } else {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            if (!isManualMode) {
                manualTurnControls.setVisibility(View.GONE);
            }
            if (deadReckoningEngine != null) {
                deadReckoningEngine.resetToCompassHeading();
            }
            setGpsActive(true);
            Toast.makeText(requireContext(), "Mode: GPS (Compass)", Toast.LENGTH_SHORT).show();
        }
    }

    private GeoPoint calculatePoint5MetersBack() {
        if (recentGPSPoints.size() < 2) {
            return null;
        }
        
        GeoPoint lastPoint = recentGPSPoints.get(recentGPSPoints.size() - 1);
        GeoPoint secondLastPoint = recentGPSPoints.get(recentGPSPoints.size() - 2);
        
        double bearing = calculateBearing(secondLastPoint.getLatitude(), secondLastPoint.getLongitude(),
                                          lastPoint.getLatitude(), lastPoint.getLongitude());
        
        double backBearing = (bearing + 180) % 360;
        
        return calculateEstimatedPosition(
            lastPoint.getLatitude(),
            lastPoint.getLongitude(),
            backBearing,
            5.0
        );
    }

    private double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double lon1Rad = Math.toRadians(lon1);
        double lon2Rad = Math.toRadians(lon2);
        
        double dLon = lon2Rad - lon1Rad;
        
        double y = Math.sin(dLon) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - 
                   Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon);
        
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    private void centerOnGPS() {
        if (forceNoGPSMode) {
            if (currentPosition != null) {
                mapView.getController().animateTo(currentPosition);
            }
            return;
        }
        if (lastKnownGPS != null) {
            IMapController mapController = mapView.getController();
            mapController.animateTo(new GeoPoint(lastKnownGPS.getLatitude(), lastKnownGPS.getLongitude()));
            mapController.setZoom(19.0);
        } else {
            Toast.makeText(requireContext(), R.string.searching_gps, Toast.LENGTH_SHORT).show();
            startLocationUpdates();
        }
    }

    private void toggleTurnMode() {
        turnModePrefs.toggleTurnMode();
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
        if (deadReckoningEngine != null) {
            deadReckoningEngine.setCompassFollow(!isManualMode);
        }
        updateTurnModeUI();
        
        String mode = isManualMode ? "Manual" : "Auto";
        Toast.makeText(requireContext(), "Mode: " + mode, Toast.LENGTH_SHORT).show();
    }

    private void calibrateGPS() {
        if (deadReckoningEngine != null) {
            deadReckoningEngine.resetCalibration();
            Toast.makeText(requireContext(), R.string.gps_reset, Toast.LENGTH_LONG).show();
            textGPSStatus.setText("GPS: Calibrating...");
        }
    }

    private void updateTurnModeUI() {
        if (isManualMode) {
            buttonModeToggle.setText("Mode: Manual");
            if (isTracking) {
                manualTurnControls.setVisibility(View.VISIBLE);
            }
        } else {
            buttonModeToggle.setText("Mode: Auto");
        manualTurnControls.setVisibility(View.GONE);

        if (buttonNoGPS != null) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        }
        }
    }

    private void showTurnIndicator(String text) {
        textTurnIndicator.setText(text);
        cardTurn.setVisibility(View.VISIBLE);
        
        cardTurn.removeCallbacks(hideTurnRunnable);
        cardTurn.postDelayed(hideTurnRunnable, 2000);
    }

    private final Runnable hideTurnRunnable = new Runnable() {
        @Override
        public void run() {
            cardTurn.setVisibility(View.GONE);
        }
    };

    private void initSensors() {
        sensorManager = (SensorManager) requireContext().getSystemService(requireContext().SENSOR_SERVICE);

        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorPressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
    }

    private void registerSensors() {
        if (sensorPressure != null) {
            sensorManager.registerListener(this, sensorPressure, SensorManager.SENSOR_DELAY_UI);
        }
        if (sensorGravity != null) {
            sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorMagnetic != null) {
            sensorManager.registerListener(this, sensorMagnetic, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorGyroscope != null) {
            sensorManager.registerListener(this, sensorGyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorLinearAcceleration != null) {
            sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    handleGPSUpdate(location);
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) handleGPSUpdate(location);
            });
        }
    }

    private org.osmdroid.views.overlay.Marker deadReckoningMarker;

    private void setGpsActive(boolean active) {
        if (active) {
            startLocationUpdates();
            if (locationOverlay != null) {
                locationOverlay.enableMyLocation();
                locationOverlay.enableFollowLocation();
            }
            if (deadReckoningMarker != null && mapView != null) {
                mapView.getOverlays().remove(deadReckoningMarker);
                deadReckoningMarker = null;
            }
        } else {
            stopLocationUpdates();
            if (locationOverlay != null) {
                locationOverlay.disableFollowLocation();
                locationOverlay.disableMyLocation();
            }
            if (textGPSStatus != null) {
                textGPSStatus.setText("GPS: OFF");
                textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            }
            if (textGPSAccuracy != null) {
                textGPSAccuracy.setText("—");
            }
        }
        if (mapView != null) mapView.invalidate();
    }

    private void updateDeadReckoningMarker(GeoPoint p) {
        if (mapView == null || p == null) return;
        if (deadReckoningMarker == null) {
            deadReckoningMarker = new org.osmdroid.views.overlay.Marker(mapView);
            deadReckoningMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                    org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
            deadReckoningMarker.setTitle("Estimated position (no GPS)");
            mapView.getOverlays().add(deadReckoningMarker);
        }
        deadReckoningMarker.setPosition(p);
        if (!mapView.getBoundingBox().contains(p)) {
            mapView.getController().animateTo(p);
        }
        mapView.invalidate();
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void initMap(View view) {
        mapView = view.findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        
        RotationGestureOverlay rotationGestureOverlay = new RotationGestureOverlay(requireContext(), mapView);
        rotationGestureOverlay.setEnabled(true);
        mapView.getOverlays().add(rotationGestureOverlay);
        
        IMapController mapController = mapView.getController();
        mapController.setZoom(18.0);
        
        pathOverlay = new Polyline();
        pathOverlay.getOutlinePaint().setColor(Color.parseColor("#1976D2"));
        pathOverlay.getOutlinePaint().setStrokeWidth(12.0f);
        pathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(pathOverlay);
        
        noGPSPathOverlay = new Polyline();
        noGPSPathOverlay.getOutlinePaint().setColor(Color.parseColor("#F44336"));
        noGPSPathOverlay.getOutlinePaint().setStrokeWidth(12.0f);
        noGPSPathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(noGPSPathOverlay);
        altitudeTrack = new nisargpatel.deadreckoning.view.AltitudeTrack(mapView, noGPSPathOverlay, 12.0f);

        MapEventsReceiver mReceive = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (isAddingMarker) {
                    showMarkerDialog(p);
                    return true;
                }
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };

        mapView.getOverlays().add(new MapEventsOverlay(mReceive));

        enableLocationComponent();
    }

    @SuppressLint("MissingPermission")
    private void enableLocationComponent() {
        if (hasLocationPermission()) {
            locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(requireContext()), mapView);
            locationOverlay.enableMyLocation();
            locationOverlay.enableFollowLocation();
            mapView.getOverlays().add(locationOverlay);
        }
    }

    private void updatePathOnMap() {
        if (!pathPoints.isEmpty()) {
            pathOverlay.setPoints(pathPoints);
        }
        boolean colourByAltitude = altitudeTrack != null
                && deadReckoningEngine != null && deadReckoningEngine.hasAltitude();
        if (colourByAltitude) {
            noGPSPathOverlay.setPoints(new ArrayList<>());
            altitudeTrack.setPoints(noGPSPathPoints);
        } else {
            if (altitudeTrack != null) altitudeTrack.clear();
            noGPSPathOverlay.setPoints(noGPSPathPoints);
        }
        mapView.invalidate();
    }

    private void initEngine() {
        deadReckoningEngine = new DeadReckoningEngine();
        deadReckoningEngine.setCompassFollow(!isManualMode);
        headingEstimator = new PreciseHeadingEstimator();
        tripStorage = new TripStorage(requireContext());
    }

    public void startTracking() {
        if (!hasLocationPermission()) {
            Toast.makeText(requireContext(), "GPS Permission required", Toast.LENGTH_LONG).show();
            return;
        }

        isTracking = true;
        isPaused = false;
        
        StepCounterPreferences.StepMode stepMode = stepPrefs.getStepMode();
        deadReckoningEngine.setStepMode(stepMode);
        
        deadReckoningEngine.start();
        pathPoints.clear();
        noGPSPathPoints.clear();
        startPosition = null;
        lastGPSPoint = null;
        lastDeadReckoningPoint = null;
        lastDeadReckoningDistance = 0;
        noGPSFixedHeading = 0;
        
        if (forceNoGPSMode) {
            forceNoGPSMode = false;
            setGpsActive(true);
        }
        if (buttonNoGPS != null) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        }

        if (isManualMode || forceNoGPSMode) {
            manualTurnControls.setVisibility(View.VISIBLE);
        }
        
        if (buttonPause != null) {
            buttonPause.setVisibility(View.VISIBLE);
            buttonPause.setText(R.string.pause);
            buttonPause.setIconResource(R.drawable.ic_pause);
        }
        if (buttonStartStop != null) {
            buttonStartStop.setText(R.string.stop);
            buttonStartStop.setIconResource(R.drawable.ic_stop);
        }
        if (cardDirection != null) {
            cardDirection.setVisibility(View.VISIBLE);
        }

        updatePathOnMap();
        Toast.makeText(requireContext(), "Tracking started", Toast.LENGTH_SHORT).show();
    }

    public void stopTracking() {
        isTracking = false;
        isPaused = false;
        deadReckoningEngine.stop();

        manualTurnControls.setVisibility(View.GONE);

        if (buttonPause != null) {
            buttonPause.setVisibility(View.GONE);
        }
        if (buttonStartStop != null) {
            buttonStartStop.setText(R.string.start);
            buttonStartStop.setIconResource(R.drawable.ic_play);
        }

        if (deadReckoningEngine.getCurrentTrip() != null) {
            tripStorage.saveTrip(deadReckoningEngine.getCurrentTrip());
            Toast.makeText(requireContext(), "Trip saved", Toast.LENGTH_SHORT).show();
        }

        Toast.makeText(requireContext(), "Tracking stopped", Toast.LENGTH_SHORT).show();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), 
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void handleGPSUpdate(Location location) {
        if (isPaused) return;
        
        lastKnownGPS = location;
        
        double accuracy = location.getAccuracy();
        textGPSAccuracy.setText(String.format("%.1f m", accuracy));
        
        GeoPoint gpsPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        
        if (forceNoGPSMode) {
            textGPSStatus.setText("No GPS");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            useGPSForTrace = false;
        } else if (accuracy < 15) {
            textGPSStatus.setText("GPS: OK");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorSuccess));
            useGPSForTrace = true;
            lastGPSMoveTime = System.currentTimeMillis();
        } else if (accuracy < 25) {
            textGPSStatus.setText("GPS: Fair");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            useGPSForTrace = accuracy < 20;
        } else {
            textGPSStatus.setText("GPS: Low");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            useGPSForTrace = false;
        }

        if (isTracking && !forceNoGPSMode) {
            deadReckoningEngine.calibrateWithGPS(location);

            if (useGPSForTrace && accuracy < 15) {
                if (lastGPSPoint == null) {
                    pathPoints.add(gpsPoint);
                } else {
                    double distance = distanceBetween(lastGPSPoint, gpsPoint);
                    if (distance > 1.0) {
                        pathPoints.add(gpsPoint);
                    }
                }
                lastGPSPoint = gpsPoint;
                currentPosition = gpsPoint;
                
                recentGPSPoints.add(gpsPoint);
                if (recentGPSPoints.size() > 20) {
                    recentGPSPoints.remove(0);
                }
                
                if (lastDeadReckoningPoint == null) {
                    lastDeadReckoningPoint = gpsPoint;
                    lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                    noGPSFixedHeading = deadReckoningEngine.getHeading();
                    deadReckoningEngine.setFixedHeading(noGPSFixedHeading);
                }
                
                updatePathOnMap();
            }

            if (startPosition == null && accuracy < 20) {
                startPosition = gpsPoint;
                mapView.getController().setCenter(startPosition);
            }
        }

        if (!isTracking && accuracy < 20) {
            currentPosition = gpsPoint;
        }
    }

    private double distanceBetween(GeoPoint p1, GeoPoint p2) {
        return p1.distanceToAsDouble(p2);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values = event.values.clone();

        switch (event.sensor.getType()) {
            case Sensor.TYPE_PRESSURE:
                if (deadReckoningEngine != null) {
                    deadReckoningEngine.updatePressure(values[0]);
                }
                break;
            case Sensor.TYPE_GRAVITY:
                headingEstimator.updateGravity(values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                headingEstimator.updateMagneticField(values);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                if (isTracking && !isPaused) {
                    deadReckoningEngine.updateSensors(
                            headingEstimator.getGravity(),
                            headingEstimator.getMagneticField(),
                            headingEstimator.getGyroscope(),
                            values,
                            event.timestamp
                    );
                    updateUI();
                }
                break;
        }
    }

    private void updateUI() {
        if (getActivity() == null) return;
        
        getActivity().runOnUiThread(() -> {
            textSteps.setText(String.valueOf(deadReckoningEngine.getStepCount()));
            if (deadReckoningEngine.hasAltitude()) {
                textDistance.setText(String.format(Locale.US, "%.1f m  ↕%+.1f m",
                        deadReckoningEngine.getDistance(), deadReckoningEngine.getRelativeAltitude()));
            } else {
                textDistance.setText(String.format("%.2f m", deadReckoningEngine.getDistance()));
            }
            double heading = deadReckoningEngine.getHeading();
            textHeading.setText(String.format("%.1f°", heading));

            if (imageDirectionArrow != null && cardDirection != null && isTracking) {
                float rotation = (float) -heading;
                imageDirectionArrow.setRotation(rotation);
                cardDirection.setVisibility(View.VISIBLE);
            }

            if (!isManualMode && deadReckoningEngine.isTurning()) {
                double turnAmount = deadReckoningEngine.getAccumulatedHeadingChange();
                if (turnAmount > 0) {
                    showTurnIndicator("Left " + String.format("%.0f°", Math.abs(turnAmount)));
                } else {
                    showTurnIndicator("Right " + String.format("%.0f°", Math.abs(turnAmount)));
                }
                
                if (forceNoGPSMode) {
                    noGPSFixedHeading = heading;
                    deadReckoningEngine.setFixedHeading(heading);
                }
            }

            if (forceNoGPSMode && !isManualMode) {
                noGPSFixedHeading = heading;
            }

            if (isPaused) return;

            if (isTracking && forceNoGPSMode && lastDeadReckoningPoint != null) {
                double totalDistance = deadReckoningEngine.getDistance();
                double incrementalDistance = totalDistance - lastDeadReckoningDistance;
                
                if (incrementalDistance > 0.1 && incrementalDistance < 3.0) {
                    GeoPoint newPoint = calculateEstimatedPosition(
                            lastDeadReckoningPoint.getLatitude(),
                            lastDeadReckoningPoint.getLongitude(),
                            noGPSFixedHeading,
                            incrementalDistance
                    );

                    if (newPoint != null) {
                        if (deadReckoningEngine.hasAltitude()) {
                            newPoint.setAltitude(deadReckoningEngine.getRelativeAltitude());
                        }
                        noGPSPathPoints.add(newPoint);
                        updateDeadReckoningMarker(newPoint);
                        lastDeadReckoningPoint = newPoint;
                        currentPosition = newPoint;
                        updatePathOnMap();
                    }
                }
                lastDeadReckoningDistance = totalDistance;
            }
        });
    }

    private void openDialOverlay() {
        if (dialOverlayContainer == null || degreeDialView == null) return;
        
        float currentHeading = (float) noGPSFixedHeading;
        if (deadReckoningEngine != null && isTracking) {
            currentHeading = (float) deadReckoningEngine.getHeading();
        }
        
        degreeDialView.setDegree(currentHeading);
        dialOverlayContainer.setVisibility(View.VISIBLE);
    }

    private void closeDialOverlay() {
        if (dialOverlayContainer != null) {
            dialOverlayContainer.setVisibility(View.GONE);
        }
    }

    private void confirmDialSelection() {
        if (degreeDialView != null && deadReckoningEngine != null && isTracking) {
            float selectedDegree = degreeDialView.getDegree();
            
            if (forceNoGPSMode) {
                noGPSFixedHeading = selectedDegree;
                deadReckoningEngine.setFixedHeading(selectedDegree);
                showTurnIndicator(String.format("%.0f°", selectedDegree));
            } else if (isManualMode) {
                deadReckoningEngine.setFixedHeading(selectedDegree);
            }
        }
        
        closeDialOverlay();
    }

    private GeoPoint calculateEstimatedPosition(double lat, double lon, double heading, double distanceMeters) {
        double earthRadius = 6371000;
        double headingRad = Math.toRadians(heading);

        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);

        double newLatRad = latRad + (distanceMeters * Math.cos(headingRad)) / earthRadius;
        double newLonRad = lonRad + (distanceMeters * Math.sin(headingRad)) / (earthRadius * Math.cos(latRad));

        return new GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
        registerSensors();
        if (!forceNoGPSMode) {
            startLocationUpdates();
        }
        
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
        if (deadReckoningEngine != null) {
            deadReckoningEngine.setCompassFollow(!isManualMode);
        }
        updateTurnModeUI();
        
        try {
            requireContext().registerReceiver(screenStateReceiver, screenStateFilter);
        } catch (Exception ignored) {}
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
        // Keep sensors registered for background tracking
        // sensorManager.unregisterListener(this);
        
        try {
            requireContext().unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {}
        
        stopLocationUpdates();
    }

    private boolean isScreenOn = true;
    private final IntentFilter screenStateFilter = new IntentFilter();
    {
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
    }

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            }
        }
    };

    private void toggleAddMarkerMode() {
        isAddingMarker = !isAddingMarker;
        if (isAddingMarker) {
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorSuccess));
            Toast.makeText(requireContext(), R.string.tap_location, Toast.LENGTH_SHORT).show();
        } else {
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            pendingMarkerPosition = null;
        }
    }

    private void showMarkerDialog(GeoPoint position) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_marker, null);
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setView(dialogView);
        
        android.app.AlertDialog dialog = builder.create();
        
        TextView[] emojiViews = {
            dialogView.findViewById(R.id.emoji_pin),
            dialogView.findViewById(R.id.emoji_pushpin),
            dialogView.findViewById(R.id.emoji_home),
            dialogView.findViewById(R.id.emoji_building),
            dialogView.findViewById(R.id.emoji_restaurant),
            dialogView.findViewById(R.id.emoji_pizza),
            dialogView.findViewById(R.id.emoji_coffee),
            dialogView.findViewById(R.id.emoji_shopping),
            dialogView.findViewById(R.id.emoji_hospital),
            dialogView.findViewById(R.id.emoji_fuel),
            dialogView.findViewById(R.id.emoji_hotel),
            dialogView.findViewById(R.id.emoji_park),
            dialogView.findViewById(R.id.emoji_car),
            dialogView.findViewById(R.id.emoji_plane),
            dialogView.findViewById(R.id.emoji_star),
            dialogView.findViewById(R.id.emoji_heart),
            dialogView.findViewById(R.id.emoji_red_dot),
            dialogView.findViewById(R.id.emoji_green_dot)
        };
        
        String[] emojis = {"📍", "📌", "🏠", "🏢", "🍔", "🍕", "☕", "🛒", "🏥", "⛽", "🏨", "🏞", "🚗", "✈️", "⭐", "❤️", "🔴", "🟢"};
        
        for (int i = 0; i < emojiViews.length; i++) {
            final int index = i;
            emojiViews[i].setOnClickListener(v -> {
                selectedEmoji = emojis[index];
                for (TextView tv : emojiViews) {
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                emojiViews[index].setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimaryLight));
            });
        }
        
        emojiViews[0].setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimaryLight));
        
        com.google.android.material.textfield.TextInputEditText editLabel = dialogView.findViewById(R.id.editMarkerLabel);
        
        dialogView.findViewById(R.id.buttonCancelMarker).setOnClickListener(v -> {
            dialog.dismiss();
            isAddingMarker = false;
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            pendingMarkerPosition = null;
        });
        
        dialogView.findViewById(R.id.buttonConfirmMarker).setOnClickListener(v -> {
            String label = editLabel.getText() != null ? editLabel.getText().toString().trim() : "";
            
            Marker marker = new Marker(position.getLatitude(), position.getLongitude(), selectedEmoji);
            if (!label.isEmpty()) {
                marker.setLabel(label);
            }
            
            markerStorage.saveMarker(marker);
            addMarkerToMap(marker);
            
            dialog.dismiss();
            isAddingMarker = false;
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            Toast.makeText(requireContext(), R.string.marker_added, Toast.LENGTH_SHORT).show();
        });
        
        dialog.show();
    }

    private void addMarkerToMap(Marker marker) {
        org.osmdroid.views.overlay.Marker mapMarker = new org.osmdroid.views.overlay.Marker(mapView);
        mapMarker.setPosition(new GeoPoint(marker.getLatitude(), marker.getLongitude()));
        
        String title = marker.getEmoji();
        if (marker.getLabel() != null && !marker.getLabel().isEmpty()) {
            title = marker.getLabel() + " " + marker.getEmoji();
        }
        mapMarker.setTitle(title);
        mapMarker.setSnippet(marker.getEmoji());
        mapMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
        
        mapMarker.setOnMarkerClickListener((m, mv) -> {
            m.showInfoWindow();
            return true;
        });

        mapMarker.setOnMarkerDragListener(new org.osmdroid.views.overlay.Marker.OnMarkerDragListener() {
            @Override
            public void onMarkerDrag(org.osmdroid.views.overlay.Marker m) {}

            @Override
            public void onMarkerDragEnd(org.osmdroid.views.overlay.Marker m) {
                GeoPoint pos = m.getPosition();
                for (int i = 0; i < markerOverlays.size(); i++) {
                    if (markerOverlays.get(i) == m) {
                        Marker modelMarker = mapMarkers.get(i);
                        modelMarker.setLatitude(pos.getLatitude());
                        modelMarker.setLongitude(pos.getLongitude());
                        markerStorage.updateMarker(modelMarker);
                        break;
                    }
                }
            }

            @Override
            public void onMarkerDragStart(org.osmdroid.views.overlay.Marker m) {}
        });

        mapView.getOverlays().add(mapMarker);
        markerOverlays.add(mapMarker);
        mapMarkers.add(marker);
        mapView.invalidate();
    }

    private void loadMarkers() {
        List<Marker> markers = markerStorage.getAllMarkers();
        for (Marker marker : markers) {
            addMarkerToMap(marker);
        }
    }

    private void showClearAllDialog() {
        String[] options = {getString(R.string.clear_tracks_only), getString(R.string.clear_markers_only), getString(R.string.clear_everything)};
        
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_confirmation)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        clearTracksOnly();
                        Toast.makeText(requireContext(), R.string.trips_cleared, Toast.LENGTH_SHORT).show();
                        break;
                    case 1:
                        clearMarkersOnly();
                        Toast.makeText(requireContext(), R.string.markers_cleared, Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        clearTracksOnly();
                        clearMarkersOnly();
                        Toast.makeText(requireContext(), R.string.everything_cleared, Toast.LENGTH_SHORT).show();
                        break;
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void clearTracksOnly() {
        pathPoints.clear();
        noGPSPathPoints.clear();
        updatePathOnMap();
    }

    private void clearMarkersOnly() {
        for (org.osmdroid.views.overlay.Marker m : markerOverlays) {
            mapView.getOverlays().remove(m);
        }
        markerOverlays.clear();
        mapMarkers.clear();
        markerStorage.clearAllMarkers();
        mapView.invalidate();
    }
}
