package com.example.screentime

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        info = TextView(this).apply { textSize = 22f }
        button = Button(this).apply {
            text = "Выдать доступ к статистике"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        root.addView(info)
        root.addView(button)
        setContentView(root)
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

        val (screenMs, unlocks) = todayStats()
        info.text = "Сегодня\n\n" +
            "Экран включён: ${formatDuration(screenMs)}\n" +
            "Разблокировок: $unlocks"
    }

    // Считает время с включённым экраном и число разблокировок с 00:00 до сейчас.
    private fun todayStats(): Pair<Long, Int> {
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

        var total = 0L
        var lastOn: Long? = null
        var unlocks = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> lastOn = e.timeStamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // если экран уже горел до полуночи, считаем от начала дня
                    total += e.timeStamp - (lastOn ?: start)
                    lastOn = null
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks++
            }
        }
        // экран горит прямо сейчас
        lastOn?.let { total += end - it }

        return Pair(total, unlocks)
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}
