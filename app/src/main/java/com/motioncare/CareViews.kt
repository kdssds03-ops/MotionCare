package com.motioncare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// 주행 화면의 그림 세 가지: 위에서 본 차 실내, 호흡 원, 흔들림 게이지. 상태는 MainActivity가 넣어 준다.

/** 호흡 단계: 조명 일정(4초 밝게 → 4초 어둡게)과 같은 박자. 0..1 = 밝기(원 크기), 들숨이면 true */
fun breathPhase(startMs: Long, now: Long): Pair<Float, Boolean> {
    val t = ((now - startMs).coerceAtLeast(0) % 8000L) / 4000f   // 0..2
    val inhale = t < 1f
    val k = if (inhale) t else 2f - t
    val eased = (1 - cos(k * PI).toFloat()) / 2                   // 부드럽게 커졌다 작아짐
    return eased to inhale
}

private fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

/**
 * 위에서 본 차 실내. 탑승자 자리, 공기 케어 바람, 호흡 조명(문 쪽 띠)이 켜지면 청록으로 빛난다.
 * 앞이 위쪽, 운전석은 왼쪽.
 */
class CabinView(c: Context) : View(c) {
    var seat: Seat? = Seat.FRONT_RIGHT   // null = 아직 안 고름
    var air = false
    var breathStartMs = 0L
    var breathing = false
    var reclined = false

    private val d = c.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()

    init { contentDescription = "위에서 본 차 실내" }

    fun update(seat: Seat?, air: Boolean, breathing: Boolean, breathStartMs: Long, reclined: Boolean) {
        this.seat = seat; this.air = air; this.breathing = breathing; this.breathStartMs = breathStartMs; this.reclined = reclined
        contentDescription = "위에서 본 차 실내" + (seat?.let { " · ${seatName(it)}" } ?: "") + (if (air) " · 바람 케어 켜짐" else "") + (if (breathing) " · 호흡 조명 켜짐" else "")
        invalidate()
    }

    private fun Canvas.round(l: Float, t: Float, w: Float, h: Float, rad: Float, p: Paint) { r.set(l, t, l + w, t + h); drawRoundRect(r, rad, rad, p) }

    override fun onDraw(cv: Canvas) {
        // 설계 좌표 400×700을 뷰 가운데에 맞춤
        val s = min(width / 400f, height / 700f)
        cv.save()
        cv.translate((width - 400 * s) / 2, (height - 700 * s) / 2)
        cv.scale(s, s)
        val now = System.currentTimeMillis()
        val lw = 1f / s * d   // 1dp 선 굵기 (설계 좌표 기준)

        // 차체·유리
        fill.color = T.SURFACE; cv.round(50f, 10f, 300f, 680f, 130f, fill)
        stroke.color = T.RULE; stroke.strokeWidth = 2 * lw; cv.round(50f, 10f, 300f, 680f, 130f, stroke)
        fill.color = T.RAISED
        cv.drawPath(Path().apply { moveTo(92f, 160f); quadTo(200f, 116f, 308f, 160f); lineTo(288f, 236f); quadTo(200f, 214f, 112f, 236f); close() }, fill)
        cv.drawPath(Path().apply { moveTo(110f, 582f); quadTo(200f, 598f, 290f, 582f); lineTo(304f, 630f); quadTo(200f, 652f, 96f, 630f); close() }, fill)
        cv.round(80f, 248f, 240f, 18f, 9f, fill)   // 대시보드
        stroke.color = T.FAINT; stroke.strokeWidth = 4 * lw
        cv.drawOval(RectF(110f, 272f, 182f, 292f), stroke)   // 운전대

        // 좌석: 탑승자 자리만 청록
        val front = seat == Seat.FRONT_RIGHT
        data class S(val x: Float, val y: Float, val mine: Boolean)
        val seats = listOf(S(94f, 302f, false), S(206f, 302f, front), S(94f, 470f, seat == Seat.REAR_LEFT), S(206f, 470f, seat == Seat.REAR_RIGHT))
        for (st in seats) {
            fill.color = if (st.mine) T.ACCENT_BG else T.RAISED
            cv.round(st.x, st.y, 100f, 92f, 26f, fill)
            // 등받이: 젖혔으면 조금 뒤로
            val back = if (st.mine && reclined) 14f else 0f
            cv.round(st.x + 6f, st.y + 98f + back, 88f, 30f, 14f, fill)
            if (st.mine) {
                stroke.color = T.ACCENT; stroke.strokeWidth = 3 * lw
                cv.round(st.x, st.y, 100f, 92f, 26f, stroke)
                cv.round(st.x + 6f, st.y + 98f + back, 88f, 30f, 14f, stroke)
            }
        }

        // 호흡 조명: 문 쪽 띠가 들숨에 밝아지고 날숨에 어두워짐
        val glow = when {
            breathing -> 0.2f + 0.8f * breathPhase(breathStartMs, now).first
            air -> 0.35f
            else -> 0f
        }
        fill.color = if (glow > 0) alpha(T.ACCENT, glow) else T.RAISED
        cv.round(64f, 280f, 8f, 300f, 4f, fill)
        cv.round(328f, 280f, 8f, 300f, 4f, fill)

        // 공기 케어: 송풍구가 켜지고 바람 줄이 탑승자 쪽으로 흘러감
        // 앞자리는 대시보드 송풍구, 뒷자리는 가운데 콘솔 뒤 송풍구
        val ventX = if (front) 236f else 172f
        val ventY = if (front) 250f else 448f
        fill.color = if (air) T.ACCENT else T.FAINT
        cv.round(ventX, ventY, 56f, 12f, 6f, fill)
        if (air) {
            stroke.strokeWidth = 4 * lw
            for (i in 0 until 3) {
                val k = ((now % 2400L) / 2400f + i / 3f) % 1f
                stroke.color = alpha(T.ACCENT, if (k < 0.4f) k / 0.4f else (1 - k) / 0.6f)
                val x = ventX + 8f + i * 20f; val y = ventY + 18f + k * 16f
                cv.drawPath(Path().apply { moveTo(x, y); quadTo(x + 10f, y + 12f, x, y + 24f) }, stroke)
            }
        }
        cv.restore()
        if (air || breathing) postInvalidateDelayed(50)
    }
}

/** 호흡 원: 조명과 같은 박자로 커졌다(들숨) 작아진다(날숨). 남은 시간을 가운데 아래에. */
class BreathView(c: Context) : View(c) {
    var startMs = 0L
    var untilMs = 0L
    private val d = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }

    init { contentDescription = "호흡 가이드" }

    override fun onDraw(cv: Canvas) {
        val now = System.currentTimeMillis()
        val (k, inhale) = breathPhase(startMs, now)
        val cx = width / 2f; val cy = height / 2f
        val max = min(width, height) / 2f - 6 * d
        ring.color = alpha(T.ACCENT, 0.25f + 0.45f * k); ring.strokeWidth = 2 * d
        cv.drawCircle(cx, cy, max, ring)
        val rr = max * (0.72f + 0.2f * k)
        fill.color = T.ACCENT_BG; cv.drawCircle(cx, cy, rr, fill)
        ring.color = T.ACCENT; cv.drawCircle(cx, cy, rr, ring)
        word.color = T.TEXT; word.textSize = 52 * sp
        cv.drawText(if (inhale) "들이쉬고" else "내쉬고", cx, cy + 10 * d, word)
        val left = ((untilMs - now) / 1000).toInt().coerceAtLeast(0)
        small.color = T.MUTED; small.textSize = T.SECTION * sp
        cv.drawText("남은 시간 %d:%02d".format(left / 60, left % 60), cx, cy + 58 * d, small)
        cv.drawText("소리에 맞춰 · 눈은 창밖에", cx, cy + 58 * d + small.textSize * 1.4f, small)
        contentDescription = (if (inhale) "들이쉬세요" else "내쉬세요") + " · 남은 시간 ${left}초"
        if (untilMs > now) postInvalidateOnAnimation()
    }
}

/** 흔들림 게이지: 0~15를 270° 호로. 6(공기 케어)·10(호흡 가이드) 기준 눈금과 가운데 숫자. */
class GaugeView(c: Context) : View(c) {
    var value: Double? = null
    var name = ""
    var ink = T.MUTED
    private val d = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val r = RectF()

    fun set(v: Double?, name: String, ink: Int) {
        value = v; this.name = name; this.ink = ink
        contentDescription = "흔들림 지수 " + (v?.let { "%.1f".format(it) } ?: "대기") + " · $name"
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = 22 * d
        val rad = min(width, height) / 2f - w - 22 * d
        val cx = width / 2f; val cy = height / 2f + rad * 0.12f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        arc.strokeWidth = w
        arc.color = T.RAISED; cv.drawArc(r, 135f, 270f, false, arc)
        value?.let { v ->
            val f = (v / MAX).toFloat().coerceIn(0f, 1f)
            if (f > 0.005f) { arc.color = ink; cv.drawArc(r, 135f, 270f * f, false, arc) }
        }
        // 기준 눈금
        tick.color = T.MUTED; tick.strokeWidth = 3 * d
        txt.color = T.MUTED; txt.textSize = T.SMALL * sp
        for ((at, label) in listOf(Comfort.CAUTION_MSDV to "6 공기", Comfort.HIGH_MSDV to "10 호흡")) {
            val a = Math.toRadians(135.0 + 270.0 * at / MAX)
            val c0 = cos(a).toFloat(); val s0 = sin(a).toFloat()
            cv.drawLine(cx + c0 * (rad - w), cy + s0 * (rad - w), cx + c0 * (rad + w), cy + s0 * (rad + w), tick)
            val lr = rad + w + 14 * d
            cv.drawText(label, cx + c0 * lr, cy + s0 * lr + 6 * d, txt)
        }
        big.color = T.TEXT; big.textSize = min(rad * 0.62f, 88 * sp)
        cv.drawText(value?.let { "%.1f".format(it) } ?: "—", cx, cy + big.textSize * 0.32f, big)
        txt.color = ink; txt.textSize = T.SECTION * sp; txt.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        cv.drawText(name, cx, cy + big.textSize * 0.32f + 40 * d, txt)
        txt.typeface = Typeface.DEFAULT
    }

    companion object { const val MAX = 15.0 }
}

/** 편안함 점수 고리: 0~100을 270° 호로, 가운데 숫자와 이름 */
class ScoreView(c: Context) : View(c) {
    var score = 0
    private val d = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun set(s: Int) { score = s.coerceIn(0, 100); contentDescription = "편안함 점수 ${score}점"; invalidate() }

    override fun onDraw(cv: Canvas) {
        val w = 18 * d
        val rad = min(width, height) / 2f - w
        val cx = width / 2f; val cy = height / 2f + rad * 0.1f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        arc.strokeWidth = w
        arc.color = T.RAISED; cv.drawArc(r, 135f, 270f, false, arc)
        if (score > 0) { arc.color = T.ACCENT; cv.drawArc(r, 135f, 270f * score / 100f, false, arc) }
        big.color = T.TEXT; big.textSize = min(rad * 0.7f, 84 * sp)
        cv.drawText("$score", cx, cy + big.textSize * 0.3f, big)
        txt.color = T.MUTED; txt.textSize = T.BODY * sp
        cv.drawText("편안함 점수", cx, cy + big.textSize * 0.3f + 34 * d, txt)
    }
}
