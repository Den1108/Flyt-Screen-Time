package com.example.screentime

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
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

private val BG = Color.parseColor("#0E0E10")
private val TEXT = Color.parseColor("#F2F2F2")
private val SECOND = Color.parseColor("#8A8A90")
private val TRACK = Color.parseColor("#2A2A2E")
private val ACCENT = Color.parseColor("#E53935")

// Большое кольцо: сколько экрана за день относительно цели.
class RingView(context: Context) : View(context) {
    var progress = 0f
    var big = ""
    var small = ""

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TRACK
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ACCENT
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SECOND
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val d = minOf(width, height).toFloat()
        val stroke = d * 0.06f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val r = d / 2f - stroke
        val cx = width / 2f
        val cy = height / 2f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        c.drawCircle(cx, cy, r, track)
        if (progress > 0f) {
            c.drawArc(rect, -90f, 360f * progress.coerceIn(0.01f, 1f), false, arc)
        }
        bigPaint.textSize = d * 0.12f
        smallPaint.textSize = d * 0.05f
        c.drawText(big, cx, cy + d * 0.02f, bigPaint)
        c.drawText(small, cx, cy + d * 0.02f + d * 0.09f, smallPaint)
    }
}

// Полоска из 7 клеток-дней: чем темнее красный, тем больше экрана. Нажатие выбирает день.
class DayStrip(context: Context) : View(context) {
    var values: List<Long> = emptyList()     // слева старые дни, справа сегодня
    var labels: List<String> = emptyList()
    var selected = 0
    var onPick: ((Int) -> Unit)? = null

    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = SECOND
    }
    private val hours = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }

    private fun gap(w: Int): Float = w * 0.02f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val n = maxOf(values.size, 1)
        val cw = (w - gap(w) * (n - 1)) / n
        setMeasuredDimension(w, (cw * 1.5f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n == 0) return
        val g = gap(width)
        val cw = (width - g * (n - 1)) / n
        val maxV = maxOf(values.maxOrNull() ?: 0L, 1L)
        label.textSize = cw * 0.27f
        hours.textSize = cw * 0.27f
        outline.strokeWidth = cw * 0.06f

        for (i in 0 until n) {
            val left = i * (cw + g)
            val rect = RectF(left, 0f, left + cw, cw)
            val v = values[i]
            val alpha = if (v <= 0L) 25 else (70 + 185 * (v.toFloat() / maxV)).toInt()
            cell.color = Color.argb(alpha, 229, 57, 53)
            c.drawRoundRect(rect, cw * 0.22f, cw * 0.22f, cell)
            if (i == selected) {
                c.drawRoundRect(rect, cw * 0.22f, cw * 0.22f, outline)
            }
            val txt = if (v <= 0L) "–" else String.format(Locale.US, "%.1f", v / 3600000.0)
            c.drawText(txt, rect.centerX(), rect.centerY() + hours.textSize * 0.35f, hours)
            c.drawText(labels[i], rect.centerX(), cw + label.textSize * 1.5f, label)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && values.isNotEmpty()) {
            val idx = (e.x / width * values.size).toInt().coerceIn(0, values.size - 1)
            onPick?.invoke(idx)
        }
        return true
    }
}

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var ring: RingView
    private lateinit var strip: DayStrip
    private lateinit var appsTitle: TextView
    private lateinit var apps: LinearLayout
    private lateinit var message: TextView
    private lateinit var button: Button

    private val days = 7          // сколько последних дней показываем
    private val goalMs = 4L * 3600000L   // дневная цель для кольца: 4 часа
    private var offset = 0        // 0 = сегодня, 1 = вчера, ...

    private val keyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val titleFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
    private val shortFormat = SimpleDateFormat("EEE", Locale("ru"))

    // Итоги за день: время экрана, число разблокировок и время по приложениям.
    private class DayStats(
        val screenMs: Long,
        val unlocks: Int,
        val apps: List<Pair<String, Long>>
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("flyt_screen", Context.MODE_PRIVATE)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(32))
        }

        val title = TextView(this).apply {
            text = "Flyt Screen"
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        dateText = TextView(this).apply {
            textSize = 16f
            setTextColor(SECOND)
        }
        ring = RingView(this)
        strip = DayStrip(this)
        strip.onPick = { idx ->
            offset = days - 1 - idx
            render()
        }
        appsTitle = TextView(this).apply {
            text = "ПРИЛОЖЕНИЯ"
            textSize = 13f
            letterSpacing = 0.12f
            setTextColor(SECOND)
        }
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        message = TextView(this).apply {
            textSize = 17f
            setTextColor(TEXT)
        }
        button = Button(this).apply {
            text = "Выдать доступ к статистике"
            setTextColor(Color.WHITE)
            setAllCaps(false)
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        content.addView(title)
        content.addView(dateText)
        content.addView(
            ring,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply {
                topMargin = dp(12)
            }
        )
        content.addView(
            strip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        content.addView(
            appsTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28) }
        )
        content.addView(apps)
        content.addView(
            message,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        )
        content.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { topMargin = dp(20) }
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.addView(content)
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
            val visible = View.GONE
            dateText.visibility = visible
            ring.visibility = visible
            strip.visibility = visible
            appsTitle.visibility = visible
            apps.visibility = visible
            message.visibility = View.VISIBLE
            message.text = "Нужен доступ к данным об использовании.\n\n" +
                "Нажми кнопку, найди в списке Flyt Screen и включи доступ."
            button.visibility = View.VISIBLE
            return
        }
        dateText.visibility = View.VISIBLE
        ring.visibility = View.VISIBLE
        strip.visibility = View.VISIBLE
        appsTitle.visibility = View.VISIBLE
        apps.visibility = View.VISIBLE
        message.visibility = View.GONE
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

        // кольцо
        if (s == null) {
            ring.progress = 0f
            ring.big = "—"
            ring.small = "нет данных"
        } else {
            ring.progress = s.screenMs.toFloat() / goalMs
            ring.big = formatShort(s.screenMs)
            ring.small = "${s.unlocks} разбл. · цель 4 ч"
        }
        ring.invalidate()

        // полоска дней: слева старые, справа сегодня
        val vals = ArrayList<Long>()
        val labs = ArrayList<String>()
        for (idx in 0 until days) {
            val daysAgo = days - 1 - idx
            val (st, _) = dayRange(daysAgo)
            vals.add(load(dayKey(st))?.screenMs ?: 0L)
            labs.add(shortFormat.format(Date(st)))
        }
        strip.values = vals
        strip.labels = labs
        strip.selected = days - 1 - offset
        strip.requestLayout()
        strip.invalidate()

        // приложения
        apps.removeAllViews()
        val list = s?.apps ?: emptyList()
        if (list.isEmpty()) {
            apps.addView(TextView(this).apply {
                text = "Нет данных за этот день"
                textSize = 15f
                setTextColor(SECOND)
                setPadding(0, dp(12), 0, 0)
            })
        } else {
            val maxMs = list.maxOf { it.second }
            for ((name, ms) in list) {
                apps.addView(appRow(name, ms, maxMs))
            }
        }
    }

    // Строка приложения: название, время и красная полоска пропорциональной длины.
    private fun appRow(name: String, ms: Long, maxMs: Long): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val n = TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(TEXT)
            maxLines = 1
        }
        val t = TextView(this).apply {
            text = formatDuration(ms)
            textSize = 16f
            setTextColor(SECOND)
            gravity = Gravity.END
        }
        line.addView(n, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(
            t,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(TRACK)
        }
        val fill = View(this)
        fill.setBackgroundColor(ACCENT)
        val rest = View(this)
        bar.addView(fill, LinearLayout.LayoutParams(0, dp(4), ms.toFloat()))
        bar.addView(rest, LinearLayout.LayoutParams(0, dp(4), (maxMs - ms).toFloat()))

        col.addView(line)
        col.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(6)
            }
        )
        return col
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
            val list = ArrayList<Pair<String, Long>>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                list.add(Pair(p.getString(0), p.getLong(1)))
            }
            DayStats(o.getLong("screen"), o.getInt("unlocks"), list)
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

        val list = appMs.entries
            .filter { it.value >= 60_000 }
            .sortedByDescending { it.value }
            .take(15)
            .map { Pair(label(it.key), it.value) }

        return DayStats(screenTotal, unlocks, list)
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

    private fun formatShort(ms: Long): String {
        val minutes = ms / 60000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h ч $m м" else "$m м"
    }
}
