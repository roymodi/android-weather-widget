package com.light.weather;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class WidgetConfigureActivity extends Activity {
    private static final int REQUEST_LOCATION_PERMISSIONS = 4102;
    private static final int REQUEST_BACKGROUND_LOCATION = 4103;

    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private EditText etCity;
    private String pendingCity = "";
    private boolean waitingForBackgroundSettings = false;
    private boolean configurationCompleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configure);
        setResult(RESULT_CANCELED);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            mAppWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        etCity = findViewById(R.id.et_city);
        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            pendingCity = etCity.getText().toString().trim();

            // A manually entered city is an explicit fixed location and does not
            // need device location permissions.
            if (!pendingCity.isEmpty()) {
                completeConfiguration();
                return;
            }

            requestForegroundLocation();
        });
    }

    private boolean hasForegroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestForegroundLocation() {
        if (!hasForegroundLocationPermission()) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_LOCATION_PERMISSIONS);
            return;
        }
        requestBackgroundLocation();
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || hasBackgroundLocationPermission()) {
            completeConfiguration();
            return;
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // Android 10 can show the "Allow all the time" choice in this request.
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }, REQUEST_BACKGROUND_LOCATION);
            return;
        }

        // Android 11 and later require the user to enable background access
        // from the app's Location permission settings page.
        new AlertDialog.Builder(this)
                .setTitle("Allow location all the time")
                .setMessage("To update the weather automatically when you travel, "
                        + "the widget needs background location access. On the next screen, "
                        + "open Permissions > Location and select Allow all the time. "
                        + "Your location is used only to choose local weather.")
                .setPositiveButton("Open settings", (dialog, which) -> {
                    waitingForBackgroundSettings = true;
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Not now", (dialog, which) -> {
                    Toast.makeText(this,
                            "Automatic travel location updates are disabled without Allow all the time.",
                            Toast.LENGTH_LONG).show();
                    completeConfiguration();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForBackgroundSettings && !configurationCompleted) {
            waitingForBackgroundSettings = false;
            if (hasBackgroundLocationPermission()) {
                Toast.makeText(this, "Background location enabled.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Background location not enabled. The widget will use its last saved location.",
                        Toast.LENGTH_LONG).show();
            }
            completeConfiguration();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION_PERMISSIONS) {
            if (hasForegroundLocationPermission()) {
                requestBackgroundLocation();
            } else {
                Toast.makeText(this,
                        "Location permission denied. Automatic location detection is unavailable.",
                        Toast.LENGTH_LONG).show();
                completeConfiguration();
            }
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (!hasBackgroundLocationPermission()) {
                Toast.makeText(this,
                        "Allow all the time was not granted. Automatic travel updates may not work.",
                        Toast.LENGTH_LONG).show();
            }
            completeConfiguration();
        }
    }

    private void completeConfiguration() {
        if (configurationCompleted) return;
        configurationCompleted = true;

        SharedPreferences prefs = getSharedPreferences("WeatherWidgetPrefs", MODE_PRIVATE);
        prefs.edit().putString("manual_city_" + mAppWidgetId, pendingCity).apply();

        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        WeatherWidgetProvider.updateWidget(this, manager, mAppWidgetId);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
