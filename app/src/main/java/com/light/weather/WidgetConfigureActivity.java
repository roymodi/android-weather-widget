package com.light.weather;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

public class WidgetConfigureActivity extends Activity {
    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private EditText etCity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configure);

        setResult(RESULT_CANCELED);

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            mAppWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            );
        }

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        etCity = findViewById(R.id.et_city);
        Button btnSave = findViewById(R.id.btn_save);

        btnSave.setOnClickListener(v -> {
            String city = etCity.getText().toString().trim();
            SharedPreferences prefs = getSharedPreferences("WeatherWidgetPrefs", MODE_PRIVATE);
            prefs.edit().putString("manual_city_" + mAppWidgetId, city).apply();

            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
            WeatherWidgetProvider.updateWidget(this, appWidgetManager, mAppWidgetId);

            Intent resultValue = new Intent();
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
            setResult(RESULT_OK, resultValue);
            finish();
        });
    }
}