package nisargpatel.deadreckoning.activity;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.storage.TripStorage;
import nisargpatel.deadreckoning.view.Track3DView;

/** Full-screen 3D view of a saved trip (opened from History). */
public class Track3DActivity extends AppCompatActivity {

    private static final float[] EXAGGERATIONS = {1f, 2f, 5f, 10f, 20f};

    private Track3DView view3d;
    private MaterialButton buttonExaggeration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        view3d = new Track3DView(this);
        root.addView(view3d, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        int pad = dp(8);
        bar.setPadding(pad, pad, pad, dp(36));

        buttonExaggeration = button(getString(nisargpatel.deadreckoning.R.string.v3d_btn_height, 1f));
        buttonExaggeration.setOnClickListener(v -> nextExaggeration());
        MaterialButton top = button(getString(nisargpatel.deadreckoning.R.string.v3d_btn_top));
        top.setOnClickListener(v -> view3d.setTopView());
        MaterialButton side = button(getString(nisargpatel.deadreckoning.R.string.v3d_btn_side));
        side.setOnClickListener(v -> view3d.setSideView());
        MaterialButton reset = button(getString(nisargpatel.deadreckoning.R.string.v3d_btn_reset));
        reset.setOnClickListener(v -> view3d.resetView());
        bar.addView(buttonExaggeration);
        bar.addView(top);
        bar.addView(side);
        bar.addView(reset);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(bar, lp);
        setContentView(root);

        String tripId = getIntent().getStringExtra("trip_id");
        Trip trip = tripId != null ? new TripStorage(this).getTrip(tripId) : null;
        if (trip == null || trip.getPathPoints() == null || trip.getPathPoints().size() < 2) {
            Toast.makeText(this, nisargpatel.deadreckoning.R.string.v3d_no_points, Toast.LENGTH_LONG).show();
        } else {
            setTitle(trip.getName() != null ? trip.getName() : "Trip 3D");
            view3d.setPoints(trip.getPathPoints());
        }
        updateExaggerationLabel();
    }

    private void nextExaggeration() {
        float cur = view3d.getExaggeration();
        int idx = 0;
        for (int i = 0; i < EXAGGERATIONS.length; i++) {
            if (Math.abs(EXAGGERATIONS[i] - cur) < 1e-3) idx = i;
        }
        view3d.setExaggeration(EXAGGERATIONS[(idx + 1) % EXAGGERATIONS.length]);
        updateExaggerationLabel();
    }

    private void updateExaggerationLabel() {
        buttonExaggeration.setText(getString(nisargpatel.deadreckoning.R.string.v3d_btn_height, view3d.getExaggeration()));
    }

    private MaterialButton button(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
