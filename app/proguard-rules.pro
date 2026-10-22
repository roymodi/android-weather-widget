# Keep AppWidgetProvider
-keep public class com.light.weather.WeatherWidgetProvider { *; }

# Keep Android system resources used by RemoteViews
-keepclassmembers class * extends android.appwidget.AppWidgetProvider {
    public void onUpdate(android.content.Context, android.appwidget.AppWidgetManager, int[]);
}

# Preserve JSON parsing
-keep class org.json.** { *; }