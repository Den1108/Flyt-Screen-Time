package com.example.screentime

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var button: Button
    private lateinit var nav: LinearLayout
    private lateinit var prev: Button
    private lateinit var next: Button
    private lateinit var dateText: TextView
    private lateinit var prefs: SharedPreferences

    private val days = 7          // сколько последних дней показываем
    private var offset = 0        // 0 = сегодня, 1 = вчера, ...

    private val keyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val titleFormat = SimpleDateFormat("EEE, d MMM", Locale("ru"))

    // Итоги за день: время экрана, число разблокировок и время по приложениям.
    private class DayStats(
        val screenMs: Long,
        val unlocks: Int,
        val apps: List<Pair<String, Long>>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("flyt_screen", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }

        prev = Button(this).apply {
            text = "◀"
            setOnClickListener {
                if (offset < days - 1) {
                    offset++
                    render()
                }
            }
        }
        next = Button(this).apply {
            text = "▶"
            setOnClickListener {
                if (offset > 0) {
                    offset--
                    render()
                }
            }
        }
        dateText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev)
            addView(
                dateText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(next)
        }

        info = TextView(this).apply {
            textSize = 20f
            setPadding(0, 32, 0, 0)
        }
        button = Button(this).apply {
            text = "Выдать доступ к статистике"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        root.addView(nav)
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
            nav.visibility = View.GONE
            info.text = "Нужен доступ к данным об использовании.\n\n" +
                "Нажми кнопку, найди в списке Flyt Screen и включи доступ."
            button.visibility = View.VISIBLE
            return
        }
        nav.visibility = View.VISIBLE
        button.visibility = View.GONE

        syncDays()
        render()
    }

    // Начало и конец дня; i = 0 сегодня, 1 вчера и т. д.
    private fun dayRange(i: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -i)
        }
        val start = cal.timeInMillis
        val end = if (i == 0) {
            System.currentTimeMillis()
        } else {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.timeInMillis
        }
        return Pair(start, end)
    }

    private fun dayKey(start: Long): String = keyFormat.format(Date(start))

    // Читает журнал системы за последние дни и сохраняет итоги у себя.
    private fun syncDays() {
        for (i in 0 until days) {
            val (start, end) = dayRange(i)
            val computed = computeDay(start, end)
            val hasData = computed.screenMs > 0 || computed.apps.isNotEmpty()
            if (!hasData) continue

            val key = dayKey(start)
            val old = load(key)
            // для прошлых дней не затираем более полную запись неполной
            if (i == 0 || old == null || computed.screenMs >= old.screenMs) {
                save(key, computed)
            }
        }
    }

    private fun render() {
        val (start, _) = dayRange(offset)
        val s = load(dayKey(start))

        dateText.text = when (offset) {
            0 -> "Сегодня"
            1 -> "Вчера"
            else -> titleFormat.format(Date(start))
        }
        prev.isEnabled = offset < days - 1
        next.isEnabled = offset > 0

        val sb = StringBuilder()
        if (s == null) {
            sb.append("Нет данных за этот день.\n")
            sb.append("(Система хранит события недолго, а приложение сохраняет итоги только при открытии.)\n")
        } else {
            sb.append("Экран включён: ").append(formatDuration(s.screenMs)).append("\n")
            sb.append("Разблокировок: ").append(s.unlocks).append("\n\n")
            sb.append("По приложениям:\n")
            if (s.apps.isEmpty()) {
                sb.append("пока нет данных\n")
            } else {
                for ((name, ms) in s.apps) {
                    sb.append("• ").append(name).append(" — ").append(formatDuration(ms)).append("\n")
                }
            }
        }

        sb.append("\nПоследние дни:\n")
        for (i in 0 until days) {
            val (st, _) = dayRange(i)
            val d = load(dayKey(st))
            sb.append(titleFormat.format(Date(st))).append(" — ")
            if (d == null) {
                sb.append("нет данных\n")
            } else {
                sb.append(formatDuration(d.screenMs)).append(", ")
                    .append(d.unlocks).append(" разбл.\n")
            }
        }
        info.text = sb.toString()
    }

    // Хранилище итогов по дням (SharedPreferences, только на телефоне).
    private fun save(key: String, s: DayStats) {
        val arr = JSONArray()
        for ((name, ms) in s.apps) {
            arr.put(JSONArray().put(name).put(ms))
        }
        val o = JSONObject()
            .put("screen", s.screenMs)
            .put("unlocks", s.unlocks)
            .put("apps", arr)
        prefs.edit().putString("day_$key", o.toString()).apply()
    }

    private fun load(key: String): DayStats? {
        val str = prefs.getString("day_$key", null) ?: return null
        return try {
            val o = JSONObject(str)
            val arr = o.getJSONArray("apps")
            val apps = ArrayList<Pair<String, Long>>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                apps.add(Pair(p.getString(0), p.getLong(1)))
            }
            DayStats(o.getLong("screen"), o.getInt("unlocks"), apps)
        } catch (ex: Exception) {
            null
        }
    }

    // Читает журнал событий системы за период и считает итоги.
    private fun computeDay(start: Long, end: Long): DayStats {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
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

        // экран горит и приложения открыты на конец периода
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
