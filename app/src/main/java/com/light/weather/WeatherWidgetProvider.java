package com.light.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

public class WeatherWidgetProvider extends AppWidgetProvider {

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction())) {
            // Keep background process alive long enough for GPS & Internet tasks to complete
            final PendingResult pendingResult = goAsync();
            
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            
            if (appWidgetIds == null || appWidgetIds.length == 0) {
                ComponentName provider = new ComponentName(context, WeatherWidgetProvider.class);
                appWidgetIds = appWidgetManager.getAppWidgetIds(provider);
            }

            if (appWidgetIds != null && appWidgetIds.length > 0) {
                for (int appWidgetId : appWidgetIds) {
                    updateWidget(context, appWidgetManager, appWidgetId);
                }
            }
            
            pendingResult.finish();
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        Intent intent = new Intent(context, WeatherWidgetProvider.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{appWidgetId});
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        
        views.setTextViewText(R.id.tv_city, "Updating GPS...");
        views.setTextViewText(R.id.tv_temp, "...°C");
        appWidgetManager.updateAppWidget(appWidgetId, views);

        fetchFreshGpsLocation(context, appWidgetManager, appWidgetId);
    }

    private static void fetchFreshGpsLocation(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            fallbackToManualOrError(context, appWidgetManager, appWidgetId);
            return;
        }

        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!gpsEnabled && !netEnabled) {
                fallbackToManualOrError(context, appWidgetManager, appWidgetId);
                return;
            }

            String provider = gpsEnabled ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;

            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    try {
                        locationManager.removeUpdates(this);
                    } catch (Exception ignored) {}
                    
                    if (location != null) {
                        new FetchWeatherByCoordsTask(context, appWidgetManager, appWidgetId, location.getLatitude(), location.getLongitude()).execute();
                    } else {
                        fallbackToManualOrError(context, appWidgetManager, appWidgetId);
                    }
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            locationManager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    locationManager.removeUpdates(listener);
                    Location fallbackLoc = locationManager.getLastKnownLocation(provider);
                    if (fallbackLoc != null) {
                        new FetchWeatherByCoordsTask(context, appWidgetManager, appWidgetId, fallbackLoc.getLatitude(), fallbackLoc.getLongitude()).execute();
                    } else {
                        fallbackToManualOrError(context, appWidgetManager, appWidgetId);
                    }
                } catch (Exception ignored) {}
            }, 7000);

        } catch (SecurityException e) {
            fallbackToManualOrError(context, appWidgetManager, appWidgetId);
        }
    }

    private static void fallbackToManualOrError(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences("WeatherWidgetPrefs", Context.MODE_PRIVATE);
        String manualCity = prefs.getString("manual_city_" + appWidgetId, "").trim();

        if (!manualCity.isEmpty()) {
            new FetchWeatherByCityTask(context, appWidgetManager, appWidgetId, manualCity).execute();
        } else {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
            views.setTextViewText(R.id.tv_city, "GPS Failed");
            views.setTextViewText(R.id.tv_temp, "Tap to Retry");
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    private static class FetchWeatherByCityTask extends AsyncTask<Void, Void, String> {
        private final Context context;
        private final AppWidgetManager widgetManager;
        private final int widgetId;
        private final String cityName;

        FetchWeatherByCityTask(Context c, AppWidgetManager m, int id, String city) {
            this.context = c;
            this.widgetManager = m;
            this.widgetId = id;
            this.cityName = city;
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                String encodedCity = URLEncoder.encode(cityName, "UTF-8");
                String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encodedCity + "&count=1";
                
                URL url = new URI(geoUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                if (root.has("results")) {
                    JSONArray results = root.getJSONArray("results");
                    if (results.length() > 0) {
                        JSONObject loc = results.getJSONObject(0);
                        double lat = loc.getDouble("latitude");
                        double lon = loc.getDouble("longitude");
                        String resolvedName = loc.getString("name");

                        return fetchWeatherJson(lat, lon, resolvedName);
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            updateUI(context, widgetManager, widgetId, result);
        }
    }

    private static class FetchWeatherByCoordsTask extends AsyncTask<Void, Void, String> {
        private final Context context;
        private final AppWidgetManager widgetManager;
        private final int widgetId;
        private final double lat, lon;

        FetchWeatherByCoordsTask(Context c, AppWidgetManager m, int id, double lat, double lon) {
            this.context = c;
            this.widgetManager = m;
            this.widgetId = id;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        protected String doInBackground(Void... voids) {
            String locationName = "My Location";
            try {
                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    if (address.getLocality() != null) {
                        locationName = address.getLocality();
                    } else if (address.getSubAdminArea() != null) {
                        locationName = address.getSubAdminArea();
                    } else if (address.getAdminArea() != null) {
                        locationName = address.getAdminArea();
                    }
                }
            } catch (Exception ignored) {}

            return fetchWeatherJson(lat, lon, locationName);
        }

        @Override
        protected void onPostExecute(String result) {
            updateUI(context, widgetManager, widgetId, result);
        }
    }

    private static String fetchWeatherJson(double lat, double lon, String label) {
        try {
            String apiUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true";
            URL url = new URI(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return label + "|" + sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void updateUI(Context context, AppWidgetManager widgetManager, int widgetId, String payload) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        if (payload != null && payload.contains("|")) {
            try {
                String[] parts = payload.split("\\|", 2);
                String locationName = parts[0];
                JSONObject root = new JSONObject(parts[1]);
                JSONObject current = root.getJSONObject("current_weather");
                double temp = current.getDouble("temperature");
                int weatherCode = current.getInt("weathercode");

                views.setTextViewText(R.id.tv_city, locationName);
                views.setTextViewText(R.id.tv_temp, temp + "°C");

                String emoji = "☀️";
                if (weatherCode >= 71 && weatherCode <= 77) {
                    emoji = "❄️";
                } else if (weatherCode >= 1 && weatherCode <= 3) {
                    emoji = "☁️";
                } else if (weatherCode >= 51) {
                    emoji = "🌧️";
                }
                views.setTextViewText(R.id.tv_weather_icon, emoji);

            } catch (Exception e) {
                views.setTextViewText(R.id.tv_temp, "Parse Error");
            }
        } else {
            views.setTextViewText(R.id.tv_temp, "Offline");
        }
        widgetManager.updateAppWidget(widgetId, views);
    }
}