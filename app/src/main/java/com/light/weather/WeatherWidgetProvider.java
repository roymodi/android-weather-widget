package com.light.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Color;
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
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class WeatherWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "WeatherWidget";
    private static final String PREFS_NAME = "WeatherWidgetPrefs";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long LOCATION_TIMEOUT_MS = 7_000L;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Context appContext = context.getApplicationContext();
        RemoteViews views = createWidgetViews(appContext, appWidgetId);
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String savedCity = prefs.getString("weather_city_" + appWidgetId, null);
        String savedTemp = prefs.getString("weather_temp_" + appWidgetId, null);
        String savedIcon = prefs.getString("weather_icon_" + appWidgetId, null);

        if (savedCity != null && savedTemp != null && savedIcon != null) {
            views.setTextViewText(R.id.tv_city, savedCity);
            views.setTextViewText(R.id.tv_temp, savedTemp);
            views.setTextViewText(R.id.tv_weather_icon, savedIcon);
        } else {
            views.setTextViewText(R.id.tv_city, "Getting weather...");
            views.setTextViewText(R.id.tv_temp, "--°C");
            views.setTextViewText(R.id.tv_weather_icon, "🌤️");
        }

        appWidgetManager.updateAppWidget(appWidgetId, views);

        // A city entered in the widget configuration is an explicit choice;
        // use it instead of silently replacing it with the device GPS location.
        String manualCity = prefs.getString("manual_city_" + appWidgetId, "").trim();
        if (!manualCity.isEmpty()) {
            new FetchWeatherByCityTask(appContext, appWidgetManager, appWidgetId, manualCity).execute();
            return;
        }

        fetchFreshGpsLocation(appContext, appWidgetManager, appWidgetId);
    }

    private static RemoteViews createWidgetViews(Context context, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        // Explicitly keep the widget content surface transparent on launchers that honor RemoteViews backgrounds.
        views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT);

        Intent intent = new Intent(context, WeatherWidgetProvider.class);
        // Let AppWidgetProvider dispatch this through onUpdate exactly once.
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{appWidgetId});

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        return views;
    }

    private static void fetchFreshGpsLocation(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            fallbackToManualCity(context, appWidgetManager, appWidgetId);
            return;
        }

        try {
            final String[] providers = {
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER
            };
            final Handler handler = new Handler(Looper.getMainLooper());
            final boolean[] handled = {false};

            final LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (handled[0] || location == null) return;
                    handled[0] = true;
                    handler.removeCallbacksAndMessages(this);
                    try {
                        locationManager.removeUpdates(this);
                    } catch (SecurityException ignored) {
                        // Use the location already received.
                    }
                    new FetchWeatherByCoordsTask(
                            context, appWidgetManager, appWidgetId,
                            location.getLatitude(), location.getLongitude()).execute();
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            boolean requestedUpdates = false;
            for (String provider : providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(
                                provider, 0L, 0f, listener, Looper.getMainLooper());
                        requestedUpdates = true;
                    }
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Location provider unavailable: " + provider, e);
                }
            }

            if (!requestedUpdates) {
                Location lastKnown = getLastKnownLocation(locationManager, providers);
                if (lastKnown != null) {
                    new FetchWeatherByCoordsTask(
                            context, appWidgetManager, appWidgetId,
                            lastKnown.getLatitude(), lastKnown.getLongitude()).execute();
                } else {
                    fallbackToManualCity(context, appWidgetManager, appWidgetId);
                }
                return;
            }

            Runnable timeout = new Runnable() {
                @Override
                public void run() {
                    if (handled[0]) return;
                    handled[0] = true;
                    try {
                        locationManager.removeUpdates(listener);
                    } catch (SecurityException ignored) {
                        // Continue with any cached location we can access.
                    }

                    Location lastKnown = getLastKnownLocation(locationManager, providers);
                    if (lastKnown != null) {
                        new FetchWeatherByCoordsTask(
                                context, appWidgetManager, appWidgetId,
                                lastKnown.getLatitude(), lastKnown.getLongitude()).execute();
                    } else {
                        Log.w(TAG, "No fresh or cached location available");
                        fallbackToManualCity(context, appWidgetManager, appWidgetId);
                    }
                }
            };
            handler.postAtTime(timeout, listener,
                    android.os.SystemClock.uptimeMillis() + LOCATION_TIMEOUT_MS);

        } catch (SecurityException e) {
            Log.w(TAG, "Location permission unavailable; trying configured city", e);
            fallbackToManualCity(context, appWidgetManager, appWidgetId);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to register location updates; trying cached location", e);
            Location lastKnown = getLastKnownLocation(locationManager,
                    new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER});
            if (lastKnown != null) {
                new FetchWeatherByCoordsTask(
                        context, appWidgetManager, appWidgetId,
                        lastKnown.getLatitude(), lastKnown.getLongitude()).execute();
            } else {
                fallbackToManualCity(context, appWidgetManager, appWidgetId);
            }
        }
    }

    private static Location getLastKnownLocation(LocationManager locationManager, String[] providers) {
        Location best = null;
        for (String provider : providers) {
            try {
                if (!locationManager.isProviderEnabled(provider)) continue;
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null
                        || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.d(TAG, "Cannot read last-known location from " + provider, e);
            }
        }
        return best;
    }

    private static void fallbackToManualCity(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String manualCity = prefs.getString("manual_city_" + appWidgetId, "").trim();
        if (!manualCity.isEmpty()) {
            new FetchWeatherByCityTask(context, appWidgetManager, appWidgetId, manualCity).execute();
        }
        // Keep the last successful display when neither location nor manual city is available.
    }

    private static class FetchWeatherByCityTask extends AsyncTask<Void, Void, String> {
        private final Context context;
        private final AppWidgetManager widgetManager;
        private final int widgetId;
        private final String cityName;

        FetchWeatherByCityTask(Context context, AppWidgetManager manager, int id, String city) {
            this.context = context.getApplicationContext();
            this.widgetManager = manager;
            this.widgetId = id;
            this.cityName = city;
        }

        @Override
        protected String doInBackground(Void... ignored) {
            try {
                String encodedCity = URLEncoder.encode(cityName, "UTF-8");
                String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + encodedCity + "&count=1";
                String geoJson = readHttpResponse(geoUrl);
                if (geoJson == null) return null;

                JSONObject root = new JSONObject(geoJson);
                JSONArray results = root.optJSONArray("results");
                if (results == null || results.length() == 0) return null;

                JSONObject location = results.getJSONObject(0);
                double latitude = location.getDouble("latitude");
                double longitude = location.getDouble("longitude");
                String resolvedName = location.optString("name", cityName);
                return fetchWeatherJson(latitude, longitude, resolvedName);
            } catch (Exception e) {
                Log.w(TAG, "City weather lookup failed", e);
                return null;
            }
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
        private final double latitude;
        private final double longitude;

        FetchWeatherByCoordsTask(Context context, AppWidgetManager manager, int id,
                                  double latitude, double longitude) {
            this.context = context.getApplicationContext();
            this.widgetManager = manager;
            this.widgetId = id;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        @Override
        protected String doInBackground(Void... ignored) {
            String locationName = "My Location";
            try {
                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
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
            } catch (Exception e) {
                Log.d(TAG, "Reverse geocoding unavailable; using generic location label", e);
            }

            return fetchWeatherJson(latitude, longitude, locationName);
        }

        @Override
        protected void onPostExecute(String result) {
            updateUI(context, widgetManager, widgetId, result);
        }
    }

    private static String fetchWeatherJson(double latitude, double longitude, String label) {
        String apiUrl = "https://api.open-meteo.com/v1/forecast?latitude="
                + latitude + "&longitude=" + longitude + "&current_weather=true";
        String response = readHttpResponse(apiUrl);
        return response == null ? null : label + "|" + response;
    }

    private static String readHttpResponse(String address) {
        HttpURLConnection connection = null;
        try {
            URL url = new URI(address).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "Weather API returned HTTP " + status);
                return null;
            }

            try (InputStream stream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                return body.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP request failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String weatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code >= 1 && code <= 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if ((code >= 51 && code <= 57) || (code >= 61 && code <= 67)
                || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code == 95 || code == 96 || code == 99) return "⛈️";
        return "🌤️";
    }

    private static void updateUI(Context context, AppWidgetManager widgetManager, int widgetId, String payload) {
        // A failed request never replaces the last successful weather with an "Offline" value.
        if (payload == null) return;
        int separator = payload.indexOf('|');
        if (separator <= 0 || separator == payload.length() - 1) return;

        try {
            String locationName = payload.substring(0, separator).trim();
            if (locationName.isEmpty()) return;

            JSONObject root = new JSONObject(payload.substring(separator + 1));
            JSONObject current = root.optJSONObject("current_weather");
            if (current == null || !current.has("temperature") || !current.has("weathercode")) {
                Log.w(TAG, "Weather response did not contain current weather fields");
                return;
            }

            double temp = current.getDouble("temperature");
            int weatherCode = current.getInt("weathercode");
            if (Double.isNaN(temp) || Double.isInfinite(temp)) return;

            String temperature = String.format(Locale.getDefault(), "%.1f°C", temp);
            String emoji = weatherEmoji(weatherCode);

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("weather_city_" + widgetId, locationName)
                    .putString("weather_temp_" + widgetId, temperature)
                    .putString("weather_icon_" + widgetId, emoji)
                    .apply();

            RemoteViews views = createWidgetViews(context, widgetId);
            views.setTextViewText(R.id.tv_city, locationName);
            views.setTextViewText(R.id.tv_temp, temperature);
            views.setTextViewText(R.id.tv_weather_icon, emoji);
            widgetManager.updateAppWidget(widgetId, views);
        } catch (Exception e) {
            Log.w(TAG, "Unable to parse weather response; preserving cached weather", e);
        }
    }
}
