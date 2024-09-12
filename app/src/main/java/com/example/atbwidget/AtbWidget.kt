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
    @Json(name = "NSR:Quay:75707") val quay75707: Quay? //Gløshaugen
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

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        val formattedDate = now.format(formatter)
        val encodedDate = formattedDate.replace(":", "%3A")

        var timeToGloshaugen = "N/A"
        var timeFromGloshaugen = "N/A"

        val remoteViews = RemoteViews(context.packageName, R.layout.atb_widget)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val atbWidget = ComponentName(context, AtbWidget::class.java)

        remoteViews.setViewVisibility(R.id.progressBar2, View.VISIBLE)

        appWidgetManager.updateAppWidget(atbWidget, remoteViews)

        val serviceJourneyIdToFind = "ATB:ServiceJourney:3"

        val requestUrl = "https://atb-prod.api.mittatb.no/bff/v2/departures/realtime?quayIds=NSR%3AQuay%3A72401&quayIds=NSR%3AQuay%3A75707&limit=10&timeRange=100000&startTime=$encodedDate"

        val request: Request = Request.Builder()
            .url(requestUrl)
            .build()

        okhttpclient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    val timetable = timetableJsonAdapter.fromJson(response.body!!.source())

                    val firstBusToGloshaugen = timetable?.quay72401?.departures?.entries?.firstOrNull { it.value.serviceJourneyId.startsWith(serviceJourneyIdToFind) }
                    val firstBusFromGloshaugen = timetable?.quay75707?.departures?.entries?.firstOrNull { it.value.serviceJourneyId.startsWith(serviceJourneyIdToFind) }

                    if (firstBusToGloshaugen != null) {

                        val expectedDepartureTimeFirstBusToGloshaugen = ZonedDateTime.parse(firstBusToGloshaugen.value.timeData.expectedDepartureTime)

                        timeToGloshaugen = Duration.between(now, expectedDepartureTimeFirstBusToGloshaugen).toMinutes().toString()

                    }

                    if (firstBusFromGloshaugen != null) {

                        val expectedDepartureTimeFirstBusFromGloshaugen = ZonedDateTime.parse(firstBusFromGloshaugen.value.timeData.expectedDepartureTime)

                        timeFromGloshaugen = Duration.between(now, expectedDepartureTimeFirstBusFromGloshaugen).toMinutes().toString()

                        //println("Time until departure from Gløshaugen: ${timeFromGloshaugen} min")
                    }

                    remoteViews.setTextViewText(R.id.textView, "Voll: ${timeToGloshaugen} min")
                    remoteViews.setTextViewText(R.id.textView2, "Ghg: ${timeFromGloshaugen} min")
                    remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
                }

                appWidgetManager.updateAppWidget(atbWidget, remoteViews)

            }
        })

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
            println("INTENTS_UPDATED")
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
                println("SCREEN_ON")
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


