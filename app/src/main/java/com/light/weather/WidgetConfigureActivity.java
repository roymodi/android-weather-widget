package com.light.weather;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

public class WidgetConfigureActivity extends Activity {
    private static final int REQUEST_LOCATION_PERMISSIONS = 4102;
    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private EditText etCity;
    private String pendingCity = "";

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

            // An empty city means automatic location. Request runtime permission
            // here because an AppWidgetProvider itself cannot show permission prompts.
            if (pendingCity.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQUEST_LOCATION_PERMISSIONS);
                return;
            }

            completeConfiguration();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSIONS) {
            // Complete configuration whether permission was granted or denied.
            // The provider will show/use the configured city if one was supplied.
            completeConfiguration();
        }
    }

    private void completeConfiguration() {
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
