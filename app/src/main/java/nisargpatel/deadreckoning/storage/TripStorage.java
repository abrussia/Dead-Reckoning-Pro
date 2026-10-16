package nisargpatel.deadreckoning.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import nisargpatel.deadreckoning.model.Trip;
import org.osmdroid.util.GeoPoint;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TripStorage {
    
    private static final String PREFS_NAME = "TripStorage";
    private static final String TRIPS_KEY = "trips";
    private static final String EXPORT_FOLDER = "DeadReckoning/Exports";
    
    private final SharedPreferences prefs;
    private final Context context;
    private final Gson gson;
    
    private static final String TRIPS_DIR = "trips";
    private static final Object LOCK = new Object();

    private final File tripsDir;

    public TripStorage(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.tripsDir = new File(this.context.getFilesDir(), TRIPS_DIR);
        if (!tripsDir.exists()) {
            tripsDir.mkdirs();
        }
        migrateFromPreferences();
    }

    /**
     * Trips used to live together in one SharedPreferences string, which gets slow and
     * fragile with long tracks. Each trip is now its own file; old data is moved over once.
     */
    private void migrateFromPreferences() {
        synchronized (LOCK) {
            String json = prefs.getString(TRIPS_KEY, null);
            if (json == null) return;
            try {
                Type type = new TypeToken<ArrayList<Trip>>(){}.getType();
                List<Trip> old = gson.fromJson(json, type);
                if (old != null) {
                    for (Trip t : old) {
                        if (t != null && t.getId() != null) writeTripFile(t);
                    }
                }
                prefs.edit().remove(TRIPS_KEY).apply();
            } catch (Exception ignored) {
                // keep the old data untouched if anything goes wrong
            }
        }
    }

    private File fileFor(String id) {
        return new File(tripsDir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    private void writeTripFile(Trip trip) throws IOException {
        File target = fileFor(trip.getId());
        File tmp = new File(tripsDir, target.getName() + ".tmp");
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmp), java.nio.charset.StandardCharsets.UTF_8)) {
            gson.toJson(trip, w);
        }
        if (!tmp.renameTo(target)) {
            // renameTo may fail if the target exists on some file systems
            target.delete();
            if (!tmp.renameTo(target)) throw new IOException("Cannot save trip " + trip.getId());
        }
    }

    private Trip readTripFile(File f) {
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
            return gson.fromJson(r, Trip.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Saves (or overwrites) one trip. Safe to call repeatedly during tracking (autosave). */
    public void saveTrip(Trip trip) {
        if (trip == null || trip.getId() == null) return;
        synchronized (LOCK) {
            try {
                writeTripFile(trip);
            } catch (IOException ignored) {
            }
        }
    }

    public List<Trip> getAllTrips() {
        List<Trip> trips = new ArrayList<>();
        synchronized (LOCK) {
            File[] files = tripsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    Trip t = readTripFile(f);
                    if (t != null) trips.add(t);
                }
            }
        }
        return trips;
    }

    public Trip getTrip(String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            File f = fileFor(id);
            return f.exists() ? readTripFile(f) : null;
        }
    }

    public void deleteTrip(String id) {
        if (id == null) return;
        synchronized (LOCK) {
            fileFor(id).delete();
        }
    }

    public void clearAllTrips() {
        synchronized (LOCK) {
            File[] files = tripsDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
    }

    public String exportToCSV(Trip trip) throws IOException {
        File exportDir = new File(context.getExternalFilesDir(null), EXPORT_FOLDER);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String fileName = "trip_" + sdf.format(new Date(trip.getStartTime())) + ".csv";
        File file = new File(exportDir, fileName);
        
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(trip.toCSV());
        }
        
        return file.getAbsolutePath();
    }
    
    public String exportToGPX(Trip trip) throws IOException {
        File exportDir = new File(context.getExternalFilesDir(null), EXPORT_FOLDER);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String fileName = "trip_" + sdf.format(new Date(trip.getStartTime())) + ".gpx";
        File file = new File(exportDir, fileName);
        
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(trip.toGPX());
        }
        
        return file.getAbsolutePath();
    }
    
    public List<Trip> getRecentTrips(int count) {
        List<Trip> trips = getAllTrips();
        trips.sort((t1, t2) -> Long.compare(t2.getStartTime(), t1.getStartTime()));
        
        return trips.subList(0, Math.min(count, trips.size()));
    }
    
    public int getTotalTrips() {
        return getAllTrips().size();
    }
    
    public double getTotalDistance() {
        double total = 0;
        for (Trip trip : getAllTrips()) {
            total += trip.getTotalDistance();
        }
        return total;
    }
    
    public int getTotalSteps() {
        int total = 0;
        for (Trip trip : getAllTrips()) {
            total += trip.getTotalSteps();
        }
        return total;
    }
}
