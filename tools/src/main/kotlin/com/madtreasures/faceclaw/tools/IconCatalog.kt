package com.madtreasures.faceclaw.tools

import java.io.File

/** The curated subset of Material Icons baked into the icon fonts. */
object IconCatalog {
    private val names = listOf(
        // navigation & system
        "home", "apps", "grid_view", "view_list", "dashboard", "widgets", "menu", "more_vert", "more_horiz",
        "arrow_back", "arrow_forward", "arrow_upward", "arrow_downward", "chevron_left", "chevron_right",
        "expand_less", "expand_more", "close", "check", "check_circle", "cancel", "add", "remove", "delete",
        "edit", "refresh", "sync", "search", "settings", "tune", "info", "help", "warning", "error",
        "lock", "lock_open", "visibility", "visibility_off", "power_settings_new", "restart_alt", "system_update",
        "developer_mode", "bug_report", "memory", "touch_app", "gesture", "swipe", "keyboard", "text_fields",
        "language", "translate", "star", "star_border", "favorite", "favorite_border", "bolt", "flag",
        "push_pin", "bookmark", "label", "tag", "link", "share", "download", "upload", "folder", "insert_drive_file",
        "description", "article", "subject", "notes", "list", "format_list_bulleted", "checklist",
        // status
        "battery_full", "battery_charging_full", "battery_alert", "battery_0_bar", "battery_1_bar", "battery_2_bar",
        "battery_3_bar", "battery_4_bar", "battery_5_bar", "battery_6_bar", "bluetooth", "bluetooth_connected",
        "bluetooth_disabled", "bluetooth_searching", "wifi", "wifi_off", "signal_cellular_alt", "signal_cellular_off",
        "airplanemode_active", "do_not_disturb_on", "notifications", "notifications_active", "notifications_off",
        "notifications_none", "brightness_high", "brightness_medium", "brightness_low", "brightness_auto",
        "light_mode", "dark_mode", "wb_sunny", "nights_stay", "bedtime", "watch", "headphones", "phone_android",
        "smartphone", "vibration", "volume_up", "volume_down", "volume_off", "volume_mute", "mic", "mic_off",
        "mic_none", "hearing", "speaker", "cast", "location_on", "location_off", "my_location", "gps_fixed",
        // communication
        "chat", "chat_bubble", "chat_bubble_outline", "forum", "sms", "email", "mail", "mark_email_read", "send",
        "reply", "reply_all", "phone", "call", "call_end", "call_missed", "person", "people", "group", "contacts",
        "alternate_email",
        // media
        "music_note", "queue_music", "library_music", "album", "play_arrow", "pause", "stop", "skip_next",
        "skip_previous", "fast_forward", "fast_rewind", "replay", "forward_10", "replay_10", "shuffle", "repeat",
        "repeat_one", "podcasts", "radio", "playlist_play", "equalizer", "graphic_eq", "movie", "photo", "image",
        "videocam", "camera_alt",
        // time
        "schedule", "access_time", "alarm", "alarm_on", "alarm_off", "alarm_add", "timer", "timer_off",
        "hourglass_empty", "hourglass_bottom", "hourglass_top", "av_timer", "snooze", "event", "event_note",
        "today", "calendar_today", "calendar_month", "date_range", "update", "history",
        // places & navigation
        "explore", "navigation", "near_me", "map", "directions", "directions_walk", "directions_run",
        "directions_bike", "directions_car", "directions_transit", "directions_bus", "train", "flight",
        "place", "turn_left", "turn_right", "turn_slight_left", "turn_slight_right", "turn_sharp_left",
        "turn_sharp_right", "u_turn_left", "u_turn_right", "straight", "roundabout_left", "roundabout_right",
        "merge", "fork_left", "fork_right", "ramp_left", "ramp_right", "trip_origin", "speed", "terrain",
        // weather
        "cloud", "cloud_queue", "wb_cloudy", "thunderstorm", "ac_unit", "umbrella", "water_drop", "air",
        "thermostat", "device_thermostat", "grain", "waves",
        // apps & tools
        "smart_toy", "psychology", "auto_awesome", "lightbulb", "calculate", "terminal", "code", "sports_esports",
        "extension", "school", "work", "shopping_cart", "local_cafe", "restaurant", "fitness_center",
        "monitor_heart", "favorite_outline", "science", "biotech", "straighten", "timeline", "show_chart",
        "trending_up", "trending_down", "insights", "qr_code", "qr_code_scanner", "fingerprint", "key", "vpn_key",
        "record_voice_over", "voice_chat", "subtitles", "closed_caption", "font_download", "format_size",
        "zoom_in", "zoom_out", "fullscreen", "fullscreen_exit", "crop_free", "open_in_new", "launch",
        "exit_to_app", "logout", "login", "circle", "radio_button_checked", "radio_button_unchecked",
        "check_box", "check_box_outline_blank", "toggle_on", "toggle_off", "adjust", "blur_on", "contrast",
    )

    /** Returns icon name → codepoint for every curated name the font provides. */
    fun load(codepointsFile: File): Map<String, Int> {
        val all = HashMap<String, Int>()
        codepointsFile.forEachLine { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2) all.putIfAbsent(parts[0], parts[1].toInt(16))
        }
        val result = LinkedHashMap<String, Int>()
        for (n in names) all[n]?.let { result[n] = it }
        return result
    }

    fun writeKotlin(icons: Map<String, Int>, out: File) {
        val sb = StringBuilder()
        sb.append("// Generated by `./gradlew :tools:bakeFonts` from tools/fonts/material-icons. Do not edit.\n")
        sb.append("package com.madtreasures.faceclaw.core.gfx\n\n")
        sb.append("/** Codepoints of the Material Icons baked into the `icons-<size>` fonts (Apache License 2.0). */\n")
        sb.append("@Suppress(\"unused\")\n")
        sb.append("object Icons {\n")
        for ((name, cp) in icons) {
            val ident = name.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
                .let { if (it.first().isDigit()) "Icon$it" else it }
            sb.append("    const val $ident: Int = 0x${cp.toString(16).uppercase()}\n")
        }
        sb.append("}\n")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}
