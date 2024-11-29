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
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Implementation of App Widget functionality.
 */
@JsonClass(generateAdapter = true)
data class Timetable(
    @Json(name = "NSR:Quay:72401") val quay72401: Quay?, //Voll to Gløshaugen
    @Json(name = "NSR:Quay:72402") val quay72402: Quay?, //Voll to Dragvoll
    @Json(name = "NSR:Quay:75707") val quay75707: Quay?, //Gløshaugen
    @Json(name = "NSR:Quay:71940") val quay71940: Quay?, //Høgskolen
    @Json(name = "NSR:Quay:74610") val quay74610: Quay?, //Dragvoll3
    @Json(name = "NSR:Quay:74609") val quay74609: Quay?, //Dragvoll12
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

    private val okhttpclient: OkHttpClient = OkHttpClient()

    private val moshi: Moshi = Moshi.Builder().build()
    val timetableJsonAdapter: JsonAdapter<Timetable> = moshi.adapter(Timetable::class.java)

    private fun updateAtbTransportTimesService(context: Context) {

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val stopPrefixes = listOf("Voll", "Ghg", "Høg", "Drg")
        val textViewIds = (1..stopPrefixes.size).map { index ->
            val textViewName = if (index == 1) "textView" else "textView$index"
            context.resources.getIdentifier(textViewName, "id", context.packageName)
        }.filter { it != 0 }

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        val formattedDate = now.format(formatter)
        val encodedDate = formattedDate.replace(":", "%3A")

        var textTimeFromVolltoGloshaugen: String
        var timeFromVolltoDragvoll3: Duration
        var timeFromVolltoDragvoll12: Duration
        var textTimeFromVolltoDragvoll: String
        var textTimeFromGloshaugen: String
        var textTimeFromHogskoleringen: String
        var timeFromDragvoll3: Duration
        var timeFromDragvoll12: Duration
        var textTimeFromDragvoll: String

        val fallBackMaxTime =
            ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999, ZoneId.systemDefault())
                .toString()

        val remoteViews = RemoteViews(context.packageName, R.layout.atb_widget)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val atbWidget = ComponentName(context, AtbWidget::class.java)

        val serviceJourneyIdToFindBus3 = "ATB:ServiceJourney:3"
        val serviceJourneyIdToFindBus12 = "ATB:ServiceJourney:12"

        println("Encoded date: $encodedDate")

        val requestUrl =
            "https://atb-prod.api.mittatb.no/bff/v2/departures/realtime?quayIds=NSR%3AQuay%3A72401&quayIds=NSR%3AQuay%3A72402&quayIds=NSR%3AQuay%3A75707&quayIds=NSR%3AQuay%3A71940&quayIds=NSR%3AQuay%3A74610&quayIds=NSR%3AQuay%3A74609&limit=12&timeRange=100000&startTime=$encodedDate"

        resetTextViews(textViewIds, stopPrefixes, remoteViews, appWidgetManager, atbWidget)

        if (powerManager.isPowerSaveMode && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            widgetSetErrorResponse(
                textViewIds,
                atbWidget,
                appWidgetManager,
                "Please enable unrestricted battery usage",
                remoteViews
            )
        } else {

            val request: Request = Request.Builder()
                .url(requestUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Host", "atb-prod.api.mittatb.no")
                .addHeader("atb-app-version", "1.58.1")

                .build()


            okhttpclient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    widgetSetErrorResponse(
                        textViewIds,
                        atbWidget,
                        appWidgetManager,
                        e.message,
                        remoteViews
                    )
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            widgetSetErrorResponse(
                                textViewIds,
                                atbWidget,
                                appWidgetManager,
                                "Error accessing URL: " + response.code.toString(),
                                remoteViews
                            )
                            throw IOException("Unexpected code $response")
                        }

                        val timetable = timetableJsonAdapter.fromJson(response.body!!.source())

                        val firstBusFromVolltoGloshaugen =
                            timetable?.quay72401?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus3)
                            }
                        val firstBusFromVolltoDragvoll3 =
                            timetable?.quay72402?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus3)
                            }
                        val firstBusFromVolltoDragvoll12 =
                            timetable?.quay72402?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus12)
                            }
                        val firstBusFromGloshaugen =
                            timetable?.quay75707?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus3)
                            }
                        val firstBusFromHogskoleringen =
                            timetable?.quay71940?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus3)
                            }
                        val firstBusFromDragvoll3 =
                            timetable?.quay74610?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus3)
                            }
                        val firstBusFromDragvoll12 =
                            timetable?.quay74609?.departures?.entries?.firstOrNull {
                                it.value.serviceJourneyId.startsWith(serviceJourneyIdToFindBus12)
                            }

                        if (firstBusFromVolltoGloshaugen != null) {

                            val expectedDepartureTimeFirstBusFromVolltoGloshaugen =
                                ZonedDateTime.parse(firstBusFromVolltoGloshaugen.value.timeData.expectedDepartureTime)
                            textTimeFromVolltoGloshaugen =
                                Duration.between(
                                    now,
                                    expectedDepartureTimeFirstBusFromVolltoGloshaugen
                                )
                                    .toMinutes().toString()
                        } else {
                            textTimeFromVolltoGloshaugen = "N/A"
                        }

                        if (firstBusFromVolltoDragvoll3 != null || firstBusFromVolltoDragvoll12 != null) {

                            val expectedDepartureTimeFirstBusFromVolltoDragvoll3 =
                                ZonedDateTime.parse(
                                    firstBusFromVolltoDragvoll3?.value?.timeData?.expectedDepartureTime
                                        ?: fallBackMaxTime
                                )
                            timeFromVolltoDragvoll3 = Duration.between(
                                now,
                                expectedDepartureTimeFirstBusFromVolltoDragvoll3
                            )
                            val expectedDepartureTimeFirstBusFromVolltoDragvoll12 =
                                ZonedDateTime.parse(
                                    firstBusFromVolltoDragvoll12?.value?.timeData?.expectedDepartureTime
                                        ?: fallBackMaxTime
                                )
                            timeFromVolltoDragvoll12 = Duration.between(
                                now,
                                expectedDepartureTimeFirstBusFromVolltoDragvoll12
                            )

                            if (timeFromVolltoDragvoll3 < timeFromVolltoDragvoll12) {
                                textTimeFromVolltoDragvoll =
                                    timeFromVolltoDragvoll3.toMinutes().toString() + " min"
                            } else {
                                textTimeFromVolltoDragvoll =
                                    timeFromVolltoDragvoll12.toMinutes().toString() + " min"
                            }

                        } else {
                            textTimeFromVolltoDragvoll = "N/A"
                        }
                        if (firstBusFromGloshaugen != null) {

                            val expectedDepartureTimeFirstBusFromGloshaugen =
                                ZonedDateTime.parse(firstBusFromGloshaugen.value.timeData.expectedDepartureTime)
                            textTimeFromGloshaugen =
                                Duration.between(now, expectedDepartureTimeFirstBusFromGloshaugen)
                                    .toMinutes().toString() + " min"

                        } else {
                            textTimeFromGloshaugen = "N/A"
                        }

                        if (firstBusFromHogskoleringen != null) {

                            val expectedDepartureTimeFirstBusFromHogskoleringen =
                                ZonedDateTime.parse(firstBusFromHogskoleringen.value.timeData.expectedDepartureTime)
                            textTimeFromHogskoleringen = Duration.between(
                                now,
                                expectedDepartureTimeFirstBusFromHogskoleringen
                            ).toMinutes().toString() + " min"

                        } else {
                            textTimeFromHogskoleringen = "N/A"
                        }

                        if (firstBusFromDragvoll3 != null || firstBusFromDragvoll12 != null) {

                            val expectedDepartureTimeFirstBusFromDragvoll3 =
                                ZonedDateTime.parse(
                                    firstBusFromDragvoll3?.value?.timeData?.expectedDepartureTime
                                        ?: fallBackMaxTime
                                )
                            timeFromDragvoll3 =
                                Duration.between(now, expectedDepartureTimeFirstBusFromDragvoll3)
                            val expectedDepartureTimeFirstBusFromDragvoll12 =
                                ZonedDateTime.parse(
                                    firstBusFromDragvoll12?.value?.timeData?.expectedDepartureTime
                                        ?: fallBackMaxTime
                                )
                            timeFromDragvoll12 =
                                Duration.between(now, expectedDepartureTimeFirstBusFromDragvoll12)

                            if (timeFromDragvoll3 < timeFromDragvoll12) {
                                textTimeFromDragvoll =
                                    timeFromDragvoll3.toMinutes().toString() + " min (3)"
                            } else {
                                textTimeFromDragvoll =
                                    timeFromDragvoll12.toMinutes().toString() + " min (12)"
                            }

                        } else {
                            textTimeFromDragvoll = "N/A"
                        }

                        remoteViews.setTextViewText(
                            R.id.textView,
                            "Voll: $textTimeFromVolltoGloshaugen / $textTimeFromVolltoDragvoll"
                        )
                        remoteViews.setTextViewText(R.id.textView2, "Ghg: $textTimeFromGloshaugen")
                        remoteViews.setTextViewText(
                            R.id.textView3,
                            "Høg: $textTimeFromHogskoleringen"
                        )
                        remoteViews.setTextViewText(R.id.textView4, "Drg: $textTimeFromDragvoll")

                        remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
                        appWidgetManager.updateAppWidget(atbWidget, remoteViews)

                    }
                }
            })
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        super.onReceive(context, intent)

        if ("BUTTON_CLICK" == intent?.action) {
            val vibrator = (context ?: return).getSystemService(Vibrator::class.java)

            val vibrationAttributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION)
                .setFlags(
                    VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY,
                    VibrationAttributes.USAGE_CLASS_MASK
                )
                .build()

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
            val updateWidgetIntent = Intent(context, AtbWidget::class.java).apply {
                action = "BUTTON_CLICK"
            }
            val updateWidgetPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                updateWidgetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.button, updateWidgetPendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
    /*
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)

        val views = RemoteViews(context.packageName, R.layout.atb_widget)

        val updateWidgetIntent = Intent(context, AtbWidget::class.java).apply {
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
        context.registerReceiver(
            screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
            Context.RECEIVER_EXPORTED
        )
        context.registerReceiver(
            screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT),
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Unregister the receiver when the widget is disabled
        context.unregisterReceiver(screenReceiver)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Unregister the receiver when the widget is deleted
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

     */

fun resetTextViews(
    textViewIds: List<Int>,
    stopPrefixes: List<String>,
    remoteViews: RemoteViews,
    appWidgetManager: AppWidgetManager,
    atbWidget: ComponentName
) {

    for (i in textViewIds.indices) {
        remoteViews.setTextViewText(
            textViewIds[i],
            "${stopPrefixes[i]}: -"
        )
    }
    remoteViews.setViewVisibility(R.id.errorTextView, View.GONE)
    remoteViews.setViewVisibility(R.id.progressBar2, View.VISIBLE)
    appWidgetManager.updateAppWidget(atbWidget, remoteViews)
}

fun widgetSetErrorResponse(
    textViewIds: List<Int>,
    atbWidget: ComponentName,
    appWidgetManager: AppWidgetManager,
    errorMessage: String?,
    remoteViews: RemoteViews
) {
    for (i in textViewIds.indices) {
        remoteViews.setTextViewText(textViewIds[i], "")
    }
    remoteViews.setViewVisibility(R.id.errorTextView, View.VISIBLE)
    remoteViews.setTextViewText(R.id.errorTextView, errorMessage)
    remoteViews.setViewVisibility(R.id.progressBar2, View.INVISIBLE)
    appWidgetManager.updateAppWidget(atbWidget, remoteViews)
}

