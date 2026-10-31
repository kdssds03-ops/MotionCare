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

// true-black-flat 스타일 (taste-skill). 차갑고 어두운 바탕, 데이터 색은 하늘색 하나.
// 차량 화면은 보는 거리가 멀어 시트보다 크게: Pleos 시스템 UI 본문(약 22)에 맞춤. 가장 큰 숫자도 48 미만.
object T {
    const val GROUND = 0xFF0C0D0F.toInt()
    const val SURFACE = 0xFF15171A.toInt()
    const val RAISED = 0xFF25282C.toInt()
    const val RULE = 0xFF22252A.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFFB4B4B4.toInt()
    const val FAINT = 0xFF949494.toInt()
    const val DATA = 0xFF4CB3F0.toInt()          // 공기의 하늘색: 위험 지수 선에만
    const val POS_BG = 0xFF172A1D.toInt(); const val POS = 0xFF3FC07A.toInt()
    const val WARN_BG = 0xFF2B2414.toInt(); const val WARN = 0xFFE5A93B.toInt()
    const val NEG_BG = 0xFF2C1A20.toInt(); const val NEG = 0xFFE5566F.toInt()

    const val TITLE = 32f; const val SECTION = 24f; const val LEAD = 46f; const val KPI = 34f
    const val BODY = 22f; const val SMALL = 18f
    const val GAP = 16; const val PAD = 26; const val RADIUS = 20
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
    background = fill(T.SURFACE, dp(T.RADIUS).toFloat())
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

/** 버튼: primary = 흰 알약에 검은 글자(반전), 나머지는 테두리만 */
fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit) = label(T.BODY, if (primary) 0xFF000000.toInt() else T.TEXT, 500, text).apply {
    background = if (primary) fill(T.TEXT, dp(999).toFloat()) else fill(0, dp(999).toFloat(), dp(1), 0xFF4A4E54.toInt())
    gravity = Gravity.CENTER
    minHeight = dp(48)
    setPadding(dp(24), 0, dp(24), 0)
    setOnClickListener { onClick() }
}

fun Context.spacer() = View(this)

/** 최근 3분 멀미 위험 지수. 주의·위험 기준선과 케어 실행 시점을 함께 그린다. */
class TraceView(c: Context) : View(c) {
    val values = FloatArray(CAP)
    var count = 0          // 지금까지 받은 샘플 수
    val marks = ArrayList<Pair<Int, String>>()   // (샘플 번호, 이름)

    fun push(v: Double) { values[count % CAP] = v.toFloat(); count++; invalidate() }
    fun mark(name: String) { marks += count to name; invalidate() }
    fun clear() { count = 0; marks.clear(); invalidate() }

    private val d = c.resources.displayMetrics.density
    private val grid = Paint().apply { color = 0xFF1F2226.toInt(); strokeWidth = d }
    private val base = Paint().apply { color = 0xFF34383E.toInt(); strokeWidth = 2 * d }
    private val ref = Paint().apply { color = 0xFF6B6F75.toInt(); strokeWidth = d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.DATA; strokeWidth = 2.5f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.FAINT; textSize = T.SMALL * c.resources.displayMetrics.scaledDensity; fontFeatureSettings = "tnum" }
    private val note = Paint(tick).apply { color = T.MUTED }
    private val marker = Paint().apply { color = 0xFF8A8F96.toInt(); strokeWidth = d }

    override fun onDraw(cv: Canvas) {
        val n = minOf(count, CAP)
        val lane = 44 * d                       // y 눈금 자리
        val top = (if (marks.any { it.first > count - CAP }) 34 else 10) * d
        val bottom = height - 30 * d
        val l = lane; val r = width - 56 * d    // 오른쪽은 기준선 이름 자리
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
        for ((v, name) in listOf(Comfort.CAUTION_MSDV.toFloat() to "주의", Comfort.HIGH_MSDV.toFloat() to "위험")) {
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
                groupName += " · $name"
            } else { flush(); groupX = mx; groupName = name }
        }
        flush()
    }

    companion object { const val CAP = 360 }   // 0.5초 × 360 = 3분
}

/** 제품 마크: 하늘색 물결 한 줄 */
class MarkView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.DATA; strokeWidth = 3 * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
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
