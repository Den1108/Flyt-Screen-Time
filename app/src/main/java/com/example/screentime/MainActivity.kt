package com.example.screentime

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var button: Button

    // Итоги за день: время экрана, число разблокировок и время по приложениям.
    private class DayStats(
        val screenMs: Long,
        val unlocks: Int,
        val apps: List<Pair<String, Long>>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        info = TextView(this).apply { textSize = 20f }
        button = Button(this).apply {
            text = "Выдать доступ к статистике"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        root.addView(info)
        root.addView(button)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun refresh() {
        if (!hasUsageAccess()) {
            info.text = "Нужен доступ к данным об использовании.\n\n" +
                "Нажми кнопку, найди в списке Flyt Screen и включи доступ."
            button.visibility = View.VISIBLE
            return
        }
        button.visibility = View.GONE

        val s = todayStats()
        val sb = StringBuilder()
        sb.append("Сегодня\n\n")
        sb.append("Экран включён: ").append(formatDuration(s.screenMs)).append("\n")
        sb.append("Разблокировок: ").append(s.unlocks).append("\n\n")
        sb.append("По приложениям:\n")
        if (s.apps.isEmpty()) {
            sb.append("пока нет данных")
        } else {
            for ((name, ms) in s.apps) {
                sb.append("• ").append(name).append(" — ").append(formatDuration(ms)).append("\n")
            }
        }
        info.text = sb.toString()
    }

    // Читает журнал событий системы с 00:00 до сейчас и считает итоги.
    private fun todayStats(): DayStats {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()

        var screenTotal = 0L
        var screenOn: Long? = null
        var unlocks = 0

        val depth = HashMap<String, Int>()      // сколько окон приложения открыто
        val since = HashMap<String, Long>()     // когда приложение вышло на передний план
        val appMs = HashMap<String, Long>()     // накопленное время по приложениям

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            val pkg = e.packageName ?: ""

            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = t
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenTotal += t - (screenOn ?: start)
                    screenOn = null
                    // экран погас: закрываем все открытые приложения
                    for ((p, s) in since) {
                        appMs[p] = (appMs[p] ?: 0L) + (t - s)
                    }
                    since.clear()
                    depth.clear()
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks++
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val d = depth[pkg] ?: 0
                    if (d == 0) since[pkg] = t
                    depth[pkg] = d + 1
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val d = depth[pkg] ?: 0
                    if (d > 0) {
                        depth[pkg] = d - 1
                        if (d - 1 == 0) {
                            val s = since.remove(pkg)
                            if (s != null) appMs[pkg] = (appMs[pkg] ?: 0L) + (t - s)
                        }
                    }
                }
            }
        }

        // экран горит и приложения открыты прямо сейчас
        screenOn?.let { screenTotal += end - it }
        for ((p, s) in since) {
            appMs[p] = (appMs[p] ?: 0L) + (end - s)
        }

        val apps = appMs.entries
            .filter { it.value >= 60_000 }
            .sortedByDescending { it.value }
            .take(15)
            .map { Pair(label(it.key), it.value) }

        return DayStats(screenTotal, unlocks, apps)
    }

    // Человеческое название приложения; если не нашли, показываем имя пакета.
    private fun label(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (ex: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}
