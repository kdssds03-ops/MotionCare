package com.motioncare

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/**
 * 차량용 테마: 야간이 기본 인상, 주간도 톤을 낮춘 회색 바탕. 크롬은 무채색, 색은 상태에만.
 * ACCENT(청록) = 차가 케어하는 중, WARN(주황) = 흔들림 경고·"속이 안 좋아요".
 * Pleos UI 가이드: 대비 4.5:1 이상(UiContrastTest), 텍스트 18sp 이상, 터치 64dp 이상.
 */
data class Palette(
    val ground: Int, val surface: Int, val raised: Int, val rule: Int,
    val text: Int, val muted: Int, val faint: Int, val data: Int,
    val primaryBg: Int, val primaryText: Int, val secondaryBg: Int,
    val pos: Int, val posBg: Int, val warn: Int, val warnBg: Int, val neg: Int, val negBg: Int,
    val sickBg: Int, val sickText: Int,
    val accent: Int, val accentBg: Int,
) {
    companion object {
        private fun c(v: Long) = v.toInt()
        // 대비는 UiContrastTest가 확인
        val DAY = Palette(
            ground = c(0xFFEEF1F4), surface = c(0xFFFFFFFF), raised = c(0xFFE4E8EC), rule = c(0xFFE3E7EB),
            text = c(0xFF0B1015), muted = c(0xFF4E5A66), faint = c(0xFF5E6A76), data = c(0xFF1D5FBF),
            primaryBg = c(0xFF0B1015), primaryText = c(0xFFFFFFFF), secondaryBg = c(0xFFE4E8EC),
            pos = c(0xFF04250E), posBg = c(0xFFDCFAE5), warn = c(0xFF8A3B0B), warnBg = c(0xFFFFF4E0),
            neg = c(0xFF831C1E), negBg = c(0xFFFFEDEA), sickBg = c(0xFF8A3B0B), sickText = c(0xFFFFFFFF),
            accent = c(0xFF0A6E65), accentBg = c(0xFFDDF3F0),
        )
        val NIGHT = Palette(
            ground = c(0xFF0A0E13), surface = c(0xFF121820), raised = c(0xFF1B232D), rule = c(0xFF232C37),
            text = c(0xFFF2F5F7), muted = c(0xFFA3AEBA), faint = c(0xFF808C99), data = c(0xFF6AA8FF),
            primaryBg = c(0xFFF2F5F7), primaryText = c(0xFF0A0E13), secondaryBg = c(0xFF1B232D),
            pos = c(0xFF3FC07A), posBg = c(0xFF172A1D), warn = c(0xFFF2B14A), warnBg = c(0xFF2B2414),
            neg = c(0xFFEE6B82), negBg = c(0xFF2C1A20), sickBg = c(0xFFF2B14A), sickText = c(0xFF1E1405),
            accent = c(0xFF5CD6C8), accentBg = c(0xFF12302F),
        )
        fun of(night: Boolean) = if (night) NIGHT else DAY
    }
}

// 차량 화면은 보는 거리가 멀어 시트보다 크게: Pleos 시스템 UI 본문(약 22)에 맞춤. 가장 큰 숫자도 48 미만.
object T {
    var p = Palette.NIGHT   // Activity가 시작할 때 시스템 주간/야간에 맞춰 바꾼다
    val GROUND get() = p.ground
    val SURFACE get() = p.surface
    val RAISED get() = p.raised
    val RULE get() = p.rule
    val TEXT get() = p.text
    val MUTED get() = p.muted
    val FAINT get() = p.faint
    val DATA get() = p.data
    val POS_BG get() = p.posBg; val POS get() = p.pos
    val WARN_BG get() = p.warnBg; val WARN get() = p.warn
    val NEG_BG get() = p.negBg; val NEG get() = p.neg
    val ACCENT get() = p.accent; val ACCENT_BG get() = p.accentBg


    const val TITLE = 30f; const val SECTION = 24f; const val LEAD = 44f; const val KPI = 36f
    const val STATE = 64f   // 주행 중 상태 한 마디: 한눈에 읽고 다시 창밖을 보도록 가장 크게 (숫자 아님)
    const val BODY = 20f; const val SMALL = 18f
    const val GAP = 16; const val PAD = 24; const val EDGE = 32; const val RADIUS = 20; const val CONTROL_R = 16
    const val TOUCH = 64; const val PRIMARY_H = 72; const val SICK_H = 88; const val ROW_H = 72   // dp
}

/** 누르면 즉시(애니메이션 없이) 어두워지는 배경 */
fun Context.pressable(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1) = android.graphics.drawable.StateListDrawable().apply {
    val r = dp(radiusDp).toFloat()
    val sw = if (strokeColor != 0) dp(strokeDp) else 0
    val pressed = blend(color, if (T.p == Palette.NIGHT) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.12f)
    addState(intArrayOf(android.R.attr.state_pressed), fill(pressed, r, sw, strokeColor))
    addState(intArrayOf(-android.R.attr.state_enabled), fill(T.RAISED, r))
    addState(intArrayOf(), fill(color, r, sw, strokeColor))
}

fun Context.icon(res: Int, sizeDp: Int = 28, color: Int = T.TEXT) = android.widget.ImageView(this).apply {
    setImageResource(res)
    setColorFilter(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** 아이콘 + 글자 버튼. primary = 주 버튼(72dp), 아니면 보조 버튼(64dp) */
fun Context.button(text: String, primary: Boolean = false, icon: Int? = null, onClick: () -> Unit) = LinearLayout(this).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(if (primary) T.PRIMARY_H else T.TOUCH)
    minimumWidth = dp(160)
    setPadding(dp(24), 0, dp(24), 0)
    background = pressable(if (primary) T.p.primaryBg else T.p.secondaryBg, T.CONTROL_R)
    val ink = if (primary) T.p.primaryText else T.TEXT
    if (icon != null) addView(icon(icon, 28, ink).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) })
    addView(label(if (primary) T.SECTION else T.BODY, ink, if (primary) 600 else 500, text).apply { tag = "label" })
    isClickable = true
    setOnClickListener { onClick() }
}

/** 버튼 글자 바꾸기 (button()이 만든 뷰) */
var LinearLayout.buttonText: CharSequence
    get() = (findViewWithTag<TextView>("label")).text
    set(v) { findViewWithTag<TextView>("label").text = v }

/** 선택 칩: 선택됨 = 주 버튼 색 + 체크, 아님 = RAISED */
fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(T.TOUCH)
    setPadding(dp(22), 0, dp(22), 0)
    background = pressable(if (selected) T.p.primaryBg else T.RAISED, 999)
    val ink = if (selected) T.p.primaryText else T.TEXT
    if (selected) addView(icon(R.drawable.ic_check, 24, ink).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
    addView(label(T.BODY, ink, if (selected) 600 else 400, text))
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.label(size: Float, color: Int = T.TEXT, weight: Int = 400, text: String = "") = TextView(this).apply {
    textSize = size
    setTextColor(color)
    typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    fontFeatureSettings = "tnum"
    this.text = text
}

fun fill(color: Int, radiusPx: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radiusPx
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = fill(T.SURFACE, dp(T.RADIUS).toFloat(), dp(1), T.RULE)
    setPadding(dp(T.PAD), dp(T.PAD - 4), dp(T.PAD), dp(T.PAD))
}

fun Context.row(vararg v: View, gap: Int = T.GAP) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    v.forEachIndexed { i, child ->
        addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { if (i > 0) marginStart = dp(gap) })
    }
}

/** 상태 알약: 옅은 바탕 + 같은 색 글자 */
fun Context.pill(text: String, bg: Int, ink: Int) = label(T.SMALL, ink, 500, text).apply {
    background = fill(bg, dp(999).toFloat())
    setPadding(dp(14), dp(5), dp(14), dp(5))
}

fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
    return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
}

fun Context.spacer() = View(this)

/** 최근 3분 흔들림 지수. 조금·많이 흔들림 기준선과 케어 실행 시점을 함께 그린다. */
class TraceView(c: Context) : View(c) {
    val values = FloatArray(CAP)
    var count = 0          // 지금까지 받은 샘플 수
    val marks = ArrayList<Pair<Int, String>>()   // (샘플 번호, 이름)

    fun push(v: Double) { values[count % CAP] = v.toFloat(); count++; invalidate() }
    fun mark(name: String) { marks += count to name; invalidate() }
    fun clear() { count = 0; marks.clear(); invalidate() }

    private val d = c.resources.displayMetrics.density
    private val grid = Paint().apply { color = T.RULE; strokeWidth = d }
    private val base = Paint().apply { color = T.RAISED; strokeWidth = 2 * d }
    private val ref = Paint().apply { color = T.FAINT; strokeWidth = d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.DATA; strokeWidth = 2.5f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.FAINT; textSize = T.SMALL * c.resources.displayMetrics.scaledDensity; fontFeatureSettings = "tnum" }
    private val note = Paint(tick).apply { color = T.MUTED }
    private val marker = Paint().apply { color = T.MUTED; strokeWidth = d }

    override fun onDraw(cv: Canvas) {
        // 이 뷰는 화면을 다시 그려도 재사용되므로 주간/야간 색을 그릴 때마다 맞춤
        grid.color = T.RULE; base.color = T.RAISED; ref.color = T.FAINT; line.color = T.DATA
        tick.color = T.FAINT; note.color = T.MUTED; marker.color = T.MUTED
        val n = minOf(count, CAP)
        val lane = 44 * d                       // y 눈금 자리
        val top = (if (marks.any { it.first > count - CAP }) 34 else 10) * d
        val bottom = height - 30 * d
        val refs = listOf(Comfort.CAUTION_MSDV.toFloat() to riskName(Risk.CAUTION), Comfort.HIGH_MSDV.toFloat() to riskName(Risk.HIGH))
        val l = lane; val r = width - refs.maxOf { note.measureText(it.second) } - 16 * d   // 오른쪽은 기준선 이름 자리
        var max = 0f; for (i in 0 until n) max = maxOf(max, values[(count - n + i) % CAP])
        val yMax = maxOf(15f, ceil((max + 0.01f) / 5f) * 5f)
        fun y(v: Float) = bottom - (bottom - top) * v / yMax
        fun x(i: Int) = l + (r - l) * i / (CAP - 1).toFloat()   // i: 창 안의 위치 0..CAP-1

        var t = 0f
        while (t <= yMax) {
            cv.drawLine(l, y(t), r, y(t), if (t == 0f) base else grid)
            cv.drawText("%.0f".format(t), 4 * d, y(t) + 5 * d, tick)
            t += 5f
        }
        for ((v, name) in refs) {
            cv.drawPath(Path().apply { moveTo(l, y(v)); lineTo(r, y(v)) }, ref)
            cv.drawText(name, r + 8 * d, y(v) + 5 * d, note)
        }
        val xl = listOf("3분 전", "2분 전", "1분 전", "지금")
        xl.forEachIndexed { i, s ->
            val w = tick.measureText(s)
            val cx = l + (r - l) * i / 3f
            cv.drawText(s, (cx - w / 2).coerceIn(l, r - w), height - 6 * d, tick)
        }
        if (n == 0) {
            val s = "주행을 시작하면 최근 3분 흔들림이 그려져요"
            cv.drawText(s, (l + r - note.measureText(s)) / 2, (top + bottom) / 2, note)
            return
        }
        // 최신 샘플이 오른쪽 끝(지금)에 오도록
        val start = CAP - n
        val p = Path()
        for (i in 0 until n) {
            val px = x(start + i); val py = y(values[(count - n + i) % CAP])
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        cv.drawPath(p, line)
        // 이름이 겹칠 만큼 가까운 표시는 "공기 케어 · 호흡 가이드"처럼 한 줄로 합쳐 그린다
        var groupX = -1f; var groupName = ""
        fun flush() {
            if (groupName.isEmpty()) return
            val w = note.measureText(groupName)
            cv.drawText(groupName, (groupX + 6 * d).coerceAtMost(r - w), top - 12 * d, note)
        }
        for ((at, name) in marks) {
            val pos = at - (count - CAP)
            if (pos < 0) continue
            val mx = x(pos)
            cv.drawLine(mx, top - 6 * d, mx, bottom, marker)
            if (groupName.isNotEmpty() && mx < groupX + note.measureText(groupName) + 18 * d) {
                if (name !in groupName.split(" · ")) groupName += " · $name"   // 같은 케어가 연달아 오면 한 번만
            } else { flush(); groupX = mx; groupName = name }
        }
        flush()
    }

    companion object { const val CAP = 360 }   // 0.5초 × 360 = 3분
}

/** 제품 마크: 청록 물결 한 줄 */
class MarkView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.ACCENT; strokeWidth = 3 * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.RAISED }
    override fun onDraw(cv: Canvas) {
        val s = width.toFloat()
        cv.drawRoundRect(0f, 0f, s, s, 8 * d, 8 * d, bg)
        val path = Path(); val m = s * 0.2f
        path.moveTo(m, s / 2)
        path.cubicTo(s * 0.35f, s * 0.2f, s * 0.45f, s * 0.2f, s / 2, s / 2)
        path.cubicTo(s * 0.55f, s * 0.8f, s * 0.65f, s * 0.8f, s - m, s / 2)
        cv.drawPath(path, p)
    }
}

/** 최근 주행 점수 막대(0~100, 오래된 것 → 최신) + 멀미 피드백 점(0~10). 데모 주행은 옅게. */
class TrendView(c: Context, private val trips: List<Trip>) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.DATA }
    private val demoBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(T.DATA, T.SURFACE, 0.6f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.WARN }
    private val base = Paint().apply { color = T.RULE; strokeWidth = d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.MUTED; textSize = T.SMALL * sp; textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }

    init { minimumHeight = (220 * d).toInt() }
    private companion object { val DAY = java.text.SimpleDateFormat("M/d", java.util.Locale.KOREA) }

    override fun onDraw(cv: Canvas) {
        val top = 30 * d; val bottom = height - 30 * d
        cv.drawLine(0f, bottom, width.toFloat(), bottom, base)
        if (trips.isEmpty()) { cv.drawText("아직 기록이 없어요", width / 2f, (top + bottom) / 2, txt); return }
        val slot = width / 10f
        val bw = minOf(slot * 0.5f, 48 * d)
        trips.forEachIndexed { i, t ->
            val cx = slot * i + slot / 2
            val y = bottom - (bottom - top) * t.score.coerceIn(0, 100) / 100f
            cv.drawRoundRect(cx - bw / 2, y, cx + bw / 2, bottom, 6 * d, 6 * d, if (t.demo) demoBar else bar)
            cv.drawText("${t.score}", cx, y - 8 * d, txt)
            t.misc?.let { m -> cv.drawCircle(cx, bottom - (bottom - top) * m / 10f, 7 * d, dot) }
            cv.drawText(DAY.format(java.util.Date(t.startMs)), cx, bottom + 24 * d, txt)
        }
    }
}
