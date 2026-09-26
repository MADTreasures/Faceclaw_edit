package com.madtreasures.faceclaw.core.services

import com.madtreasures.faceclaw.core.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Countdown timers and a stopwatch that keep running while their app is closed. */
class TimerService(private val clock: Clock, private val scope: CoroutineScope) {
    data class Timer(
        val id: Int,
        val label: String,
        val durationMs: Long,
        /** Wall-clock end while running, null while paused. */
        val endsAtMs: Long?,
        val remainingWhenPausedMs: Long,
    ) {
        val running: Boolean get() = endsAtMs != null
        fun remaining(nowMs: Long): Long = endsAtMs?.let { (it - nowMs).coerceAtLeast(0) } ?: remainingWhenPausedMs
        fun progress(nowMs: Long): Float = if (durationMs <= 0) 1f else 1f - remaining(nowMs).toFloat() / durationMs
    }

    data class Stopwatch(val running: Boolean = false, val startedAtMs: Long = 0, val accumulatedMs: Long = 0, val laps: List<Long> = emptyList()) {
        fun elapsed(nowMs: Long): Long = accumulatedMs + if (running) nowMs - startedAtMs else 0
    }

    private val _timers = MutableStateFlow<List<Timer>>(emptyList())
    val timers: StateFlow<List<Timer>> = _timers.asStateFlow()
    private val _stopwatch = MutableStateFlow(Stopwatch())
    val stopwatch: StateFlow<Stopwatch> = _stopwatch.asStateFlow()
    private val _finished = MutableSharedFlow<Timer>(extraBufferCapacity = 8)
    val finished: SharedFlow<Timer> = _finished
    private var nextId = 1
    private var watcher: Job? = null

    fun start(durationMs: Long, label: String = defaultLabel(durationMs)): Timer {
        val t = Timer(nextId++, label, durationMs, clock.nowMs() + durationMs, durationMs)
        _timers.update { it + t }
        rescheduleWatcher()
        return t
    }

    fun pause(id: Int) = mutate(id) { t -> if (t.running) t.copy(endsAtMs = null, remainingWhenPausedMs = t.remaining(clock.nowMs())) else t }
    fun resume(id: Int) = mutate(id) { t -> if (!t.running) t.copy(endsAtMs = clock.nowMs() + t.remainingWhenPausedMs) else t }
    fun addTime(id: Int, ms: Long) = mutate(id) { t ->
        if (t.running) t.copy(endsAtMs = t.endsAtMs!! + ms, durationMs = t.durationMs + ms) else t.copy(remainingWhenPausedMs = t.remainingWhenPausedMs + ms, durationMs = t.durationMs + ms)
    }

    fun cancel(id: Int) {
        _timers.update { list -> list.filter { it.id != id } }
        rescheduleWatcher()
    }

    fun stopwatchToggle() = _stopwatch.update { s ->
        val now = clock.nowMs()
        if (s.running) s.copy(running = false, accumulatedMs = s.elapsed(now)) else s.copy(running = true, startedAtMs = now)
    }

    fun stopwatchLap() = _stopwatch.update { s -> if (s.running) s.copy(laps = listOf(s.elapsed(clock.nowMs())) + s.laps) else s }
    fun stopwatchReset() = _stopwatch.update { Stopwatch() }

    private fun mutate(id: Int, f: (Timer) -> Timer) {
        _timers.update { list -> list.map { if (it.id == id) f(it) else it } }
        rescheduleWatcher()
    }

    private fun rescheduleWatcher() {
        watcher?.cancel()
        val next = _timers.value.mapNotNull { it.endsAtMs }.minOrNull() ?: return
        watcher = scope.launch {
            delay((next - clock.nowMs()).coerceAtLeast(0))
            val now = clock.nowMs()
            val done = _timers.value.filter { it.endsAtMs != null && it.endsAtMs <= now }
            if (done.isNotEmpty()) {
                _timers.update { list -> list.filter { t -> done.none { it.id == t.id } } }
                done.forEach { _finished.tryEmit(it) }
            }
            rescheduleWatcher()
        }
    }

    companion object {
        fun defaultLabel(ms: Long): String {
            val min = ms / 60_000
            val sec = (ms / 1000) % 60
            return when {
                min >= 60 -> "${min / 60} h ${if (min % 60 > 0) "${min % 60} min" else ""}".trim()
                sec == 0L -> "$min min"
                min == 0L -> "$sec s"
                else -> "$min min $sec s"
            }
        }
    }
}
