package com.light.weather;

import android.Manifest;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.location.LocationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically gets a fresh device location and refreshes local weather.
 * Background location permission is required on Android 10 and later.
 */
public class WeatherRefreshWorker extends Worker {
    private static final String TAG = "WeatherRefreshWorker";
    private static final long LOCATION_WAIT_SECONDS = 20L;
    private static final long MAX_CACHED_LOCATION_AGE_MS = TimeUnit.HOURS.toMillis(2);

    public WeatherRefreshWorker(@NonNull Context context,
                                @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, WeatherWidgetProvider.class);
        int[] widgetIds = manager.getAppWidgetIds(provider);

        if (widgetIds == null || widgetIds.length == 0) {
            return Result.success();
        }

        boolean hasAutomaticLocationWidget = false;
        for (int widgetId : widgetIds) {
            String city = context.getSharedPreferences("WeatherWidgetPrefs", Context.MODE_PRIVATE)
                    .getString("manual_city_" + widgetId, "").trim();
            if (city.isEmpty()) hasAutomaticLocationWidget = true;
        }

        Location currentLocation = null;
        if (hasAutomaticLocationWidget) {
            if (!hasRequiredLocationPermissions(context)) {
                Log.w(TAG, "Background location permission is not granted; keeping last weather.");
            } else {
                currentLocation = getCurrentLocation(context);
            }
        }

        boolean requestFailed = false;
        for (int widgetId : widgetIds) {
            String city = context.getSharedPreferences("WeatherWidgetPrefs", Context.MODE_PRIVATE)
                    .getString("manual_city_" + widgetId, "").trim();

            boolean success;
            if (!city.isEmpty()) {
                // Respect a city explicitly entered by the user.
                success = WeatherWidgetProvider.refreshFromSavedLocation(context, manager, widgetId);
            } else if (currentLocation != null) {
                success = WeatherWidgetProvider.refreshFromCurrentLocation(
                        context, manager, widgetId, currentLocation);
            } else {
                // Do not erase the last successful display when location is unavailable.
                success = false;
            }
            if (!success) requestFailed = true;
        }

        // Do not repeatedly retry just because the user denied background permission
        // or location services are off. The next periodic cycle will check again.
        if (hasAutomaticLocationWidget && !hasRequiredLocationPermissions(context)) {
            return Result.success();
        }
        return requestFailed ? Result.retry() : Result.success();
    }

    private static boolean hasRequiredLocationPermissions(Context context) {
        boolean foreground = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!foreground) return false;

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Location getCurrentLocation(Context context) {
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;

        String[] providers = {
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(providers.length);
        AtomicReference<Location> bestLocation = new AtomicReference<>();

        try {
            for (String provider : providers) {
                try {
                    if (!manager.isProviderEnabled(provider)) {
                        latch.countDown();
                        continue;
                    }

                    LocationManagerCompat.getCurrentLocation(
                            manager, provider, null, executor, location -> {
                                if (location != null) {
                                    Location current = bestLocation.get();
                                    if (current == null
                                            || location.getTime() > current.getTime()
                                            || (Math.abs(location.getTime() - current.getTime())
                                            < TimeUnit.MINUTES.toMillis(1)
                                            && location.hasAccuracy()
                                            && (!current.hasAccuracy()
                                            || location.getAccuracy() < current.getAccuracy()))) {
                                        bestLocation.set(location);
                                    }
                                }
                                latch.countDown();
                            });
                } catch (SecurityException | IllegalArgumentException e) {
                    Log.w(TAG, "Unable to request location from " + provider, e);
                    latch.countDown();
                }
            }

            latch.await(LOCATION_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            executor.shutdownNow();
        }

        Location fresh = bestLocation.get();
        if (fresh != null) return fresh;

        // Last-known fallback is accepted only if it is not excessively old.
        Location cached = null;
        for (String provider : providers) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null
                        && System.currentTimeMillis() - candidate.getTime() <= MAX_CACHED_LOCATION_AGE_MS
                        && (cached == null || candidate.getTime() > cached.getTime())) {
                    cached = candidate;
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.d(TAG, "Cannot read cached location from " + provider, e);
            }
        }
        return cached;
    }
}
