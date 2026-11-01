package com.light.weather;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Periodic weather refresh. Uses saved coordinates so background GPS access
 * and ACCESS_BACKGROUND_LOCATION permission are not required.
 */
public class WeatherRefreshWorker extends Worker {

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

        boolean requestFailed = false;
        for (int widgetId : widgetIds) {
            if (!WeatherWidgetProvider.refreshFromSavedLocation(context, manager, widgetId)) {
                requestFailed = true;
            }
        }

        return requestFailed ? Result.retry() : Result.success();
    }
}
