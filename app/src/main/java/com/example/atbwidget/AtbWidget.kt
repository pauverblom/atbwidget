package com.example.atbwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.RemoteViews
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Implementation of App Widget functionality.
 */
@JsonClass(generateAdapter = true)
data class Timetable(
    @Json(name = "NSR:Quay:72401") val quay72401: Quay?, //Voll Studentby
    @Json(name = "NSR:Quay:75707") val quay75707: Quay?, //Gløshaugen
    @Json(name = "NSR:Quay:71940") val quay71940: Quay? //Høgskolen
)

@JsonClass(generateAdapter = true)
data class Quay(
    @Json(name = "quayId") val quayID: String,
    @Json(name = "departures") val departures: Map<String, ServiceJourney>
)

@JsonClass(generateAdapter = true)
data class ServiceJourney(
    @Json(name = "serviceJourneyId") val serviceJourneyId: String,
    @Json(name = "timeData") val timeData: TimeData
)

@JsonClass(generateAdapter = true)
data class TimeData(
    @Json(name = "realtime") val realtime: Boolean,
    @Json(name = "expectedDepartureTime") val expectedDepartureTime: String,
    @Json(name = "aimedDepartureTime") val aimedDepartureTime: String
)


class AtbWidget : AppWidgetProvider() {

    val okhttpclient = OkHttpClient()

    val moshi = Moshi.Builder().build()
    val timetableJsonAdapter = moshi.adapter(Timetable::class.java)

    fun updateAtbTransportTimesService(context: Context) {

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        val formattedDate = now.format(formatter)
        val encodedDate = formattedDate.replace(":", "%3A")

        var timeFromVoll: String
        var timeFromGloshaugen: String
        var timeFromHogskoleringen: String

        val remoteViews = RemoteViews(context.packageName, R.layout.atb_widget)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val atbWidget = ComponentName(context, AtbWidget::class.java)
        remoteViews.setTextViewText(R.id.textView, "Voll: ")
        remoteViews.setTextViewText(R.id.textView2, "Ghg: ")
        remoteViews.setTextViewText(R.id.textView3, "Høg: ")
        remoteViews.setTextViewTextSize(R.id.textView, 1, 16F)
        remoteViews.setViewVisibility(R.id.progressBar2, View.VISIBLE)

        appWidgetManager.updateAppWidget(atbWidget, remoteViews)

        val serviceJourneyIdToFind = "ATB:ServiceJourney:3"

        println("Encoded date: $encodedDate")

        val requestUrl = "https://atb-prod.api.mittatb.no/bff/v2/departures/realtime?quayIds=NSR%3AQuay%3A72401&quayIds=NSR%3AQuay%3A75707&quayIds=NSR%3AQuay%3A71940&limit=10&timeRange=100000&startTime=$encodedDate"



        if (powerManager.isPowerSaveMode && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            remoteViews.setTextViewText(R.id.textView, "")
            remoteViews.setTextViewText(R.id.textView2, "Please enable unrestricted battery usage")
            remoteViews.setTextViewText(R.id.textView3, "")
            remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
            appWidgetManager.updateAppWidget(atbWidget, remoteViews)
        }

        else {

            val request: Request = Request.Builder()
                .url(requestUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Host", "atb-prod.api.mittatb.no")
                .addHeader("atb-app-version", "1.58.1")

                .build()

            okhttpclient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {

                    remoteViews.setTextViewText(R.id.textView, e.message)
                    remoteViews.setTextViewTextSize(R.id.textView, 1, 13F)
                    remoteViews.setTextViewText(R.id.textView2, "")
                    remoteViews.setTextViewText(R.id.textView3, "")
                    remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
                    appWidgetManager.updateAppWidget(atbWidget, remoteViews)
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            remoteViews.setTextViewText(
                                R.id.textView,
                                "Voll: Err ${response.code} "
                            )
                            remoteViews.setTextViewText(
                                R.id.textView2,
                                "Ghg: Err ${response.code} "
                            )
                            remoteViews.setTextViewText(
                                R.id.textView3,
                                "Høg: Err ${response.code} "
                            )
                            remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
                            appWidgetManager.updateAppWidget(atbWidget, remoteViews)
                            throw IOException("Unexpected code $response")
                        }

                        val timetable = timetableJsonAdapter.fromJson(response.body!!.source())

                        val firstBusFromVoll =
                            timetable?.quay72401?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFind)
                            }
                        val firstBusFromGloshaugen =
                            timetable?.quay75707?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFind)
                            }
                        val firstBusFromHogskoleringen =
                            timetable?.quay71940?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFind)
                            }

                        if (firstBusFromVoll != null) {

                            val expectedDepartureTimeFirstBusFromVoll =
                                ZonedDateTime.parse(firstBusFromVoll.value.timeData.expectedDepartureTime)
                            timeFromVoll =
                                Duration.between(now, expectedDepartureTimeFirstBusFromVoll)
                                    .toMinutes().toString()
                            remoteViews.setTextViewText(R.id.textView, "Voll: $timeFromVoll min")

                        } else {
                            remoteViews.setTextViewText(R.id.textView, "Voll: N/A")
                        }

                        if (firstBusFromGloshaugen != null) {

                            val expectedDepartureTimeFirstBusFromGloshaugen =
                                ZonedDateTime.parse(firstBusFromGloshaugen.value.timeData.expectedDepartureTime)
                            timeFromGloshaugen =
                                Duration.between(now, expectedDepartureTimeFirstBusFromGloshaugen)
                                    .toMinutes().toString()
                            remoteViews.setTextViewText(
                                R.id.textView2,
                                "Ghg: $timeFromGloshaugen min"
                            )

                        } else {
                            remoteViews.setTextViewText(R.id.textView2, "Ghg: N/A")
                        }

                        if (firstBusFromHogskoleringen != null) {

                            val expectedDepartureTimeFirstBusFromHogskoleringen =
                                ZonedDateTime.parse(firstBusFromHogskoleringen.value.timeData.expectedDepartureTime)
                            timeFromHogskoleringen = Duration.between(
                                now,
                                expectedDepartureTimeFirstBusFromHogskoleringen
                            ).toMinutes().toString()
                            remoteViews.setTextViewText(
                                R.id.textView3,
                                "Høg: $timeFromHogskoleringen min"
                            )

                        } else {
                            remoteViews.setTextViewText(R.id.textView3, "Høg: N/A")
                        }

                        remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
                        appWidgetManager.updateAppWidget(atbWidget, remoteViews)

                    }
                }
            })
            }
        }

    override fun onReceive(context: Context?, intent: Intent?) {
        super.onReceive(context, intent)

        val vibrator = context!!.getSystemService(Vibrator::class.java)

        val vibrationAttributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION)
            .setFlags(
                VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY,
                VibrationAttributes.USAGE_CLASS_MASK
            )
            .build()

        if ("BUTTON_CLICK" == intent?.action) {
            try {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                    vibrationAttributes
                )

                updateAtbTransportTimesService(context)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {

            val views = RemoteViews(context.packageName, R.layout.atb_widget)
            val updateWidgetIntent = Intent(context, AtbWidget::class.java).apply{
                action = "BUTTON_CLICK"
            }
            val updateWidgetPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                updateWidgetIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.button, updateWidgetPendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)

        val views = RemoteViews(context.packageName, R.layout.atb_widget)

        val updateWidgetIntent = Intent(context, AtbWidget::class.java).apply{
            action = "BUTTON_CLICK"
        }
        val updateWidgetPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            updateWidgetIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.button, updateWidgetPendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisAppWidget = ComponentName(context.packageName, AtbWidget::class.java.name)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
                onUpdate(context, appWidgetManager, appWidgetIds)
            }
        }
    }

    override fun onEnabled(context: Context) {
        // Register the receiver when the widget is enabled
        context.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
            Context.RECEIVER_EXPORTED)
        context.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT),
            Context.RECEIVER_EXPORTED)
    }

    override fun onDisabled(context: Context) {
        // Unregister the receiver when the widget is disabled
        context.unregisterReceiver(screenReceiver)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray?, newWidgetIds: IntArray?) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, AtbWidget::class.java.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        onUpdate(context, appWidgetManager, appWidgetIds)
    }
}


