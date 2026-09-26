package com.madtreasures.faceclaw.app.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.madtreasures.faceclaw.core.platform.CalendarEvent
import com.madtreasures.faceclaw.core.platform.CalendarSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Upcoming events from the phone's calendars (READ_CALENDAR). */
class AndroidCalendar(private val context: Context, private val scope: CoroutineScope) : CalendarSource {
    private val _upcoming = MutableStateFlow<List<CalendarEvent>>(emptyList())
    override val upcoming: StateFlow<List<CalendarEvent>> = _upcoming
    private var observing = false

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                refresh()
                delay(10 * 60_000)
            }
        }
    }

    fun refresh() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch(Dispatchers.IO) { refresh() }
                }
            })
        }
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - 3_600_000)
        ContentUris.appendId(builder, now + 36 * 3_600_000L)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = ArrayList<CalendarEvent>()
        runCatching {
            context.contentResolver.query(builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val end = c.getLong(3)
                    if (end < now) continue
                    events += CalendarEvent(
                        id = "${c.getLong(0)}-${c.getLong(2)}",
                        title = c.getString(1) ?: "(no title)",
                        startMs = c.getLong(2),
                        endMs = end,
                        allDay = c.getInt(4) != 0,
                        location = c.getString(5)?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        _upcoming.value = events
    }
}
