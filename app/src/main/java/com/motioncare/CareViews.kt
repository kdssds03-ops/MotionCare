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
        cv.drawText("눈은 창밖에", cx, cy + 58 * d + small.textSize * 1.4f, small)
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

/**
 * 리포트의 지나온 길: 속도와 진행 방향으로 그린 모양 (지도 아님).
 * 흔들림이 클수록 진하고 굵은 선(색만으로 구분하지 않게). 청록 점 = 그곳에서 케어 시작. 빈 원 = 출발, 찬 원 = 도착.
 */
class RouteView(c: Context, private val route: RouteTrace) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * c.resources.displayMetrics.density; strokeJoin = Paint.Join.ROUND }
    private val icons = HashMap<Care, android.graphics.drawable.Drawable>()

    init {
        minimumHeight = (260 * d).toInt()
        val high = route.points.count { it.risk == Risk.HIGH } * 100 / route.points.size
        contentDescription = "지나온 길 · 많이 흔들린 구간 약 $high% · 케어 ${route.marks.size}번 시작"
    }

    private fun ink(r: Risk) = when (r) { Risk.LOW -> T.FAINT; Risk.CAUTION -> blend(T.WARN, T.SURFACE, 0.35f); Risk.HIGH -> T.WARN }
    private fun width(r: Risk) = when (r) { Risk.LOW -> 4f; Risk.CAUTION -> 7f; Risk.HIGH -> 10f } * d

    override fun onDraw(cv: Canvas) {
        val pts = route.points
        val legendH = 44 * d
        val pad = 36 * d
        val l = pad; val t = pad; val r = width - pad - 110 * d; val b = height - legendH - pad   // 오른쪽은 케어 이름 자리
        note.color = T.MUTED; note.textSize = T.SMALL * sp
        drawLegend(cv, height - 18 * d)
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        if (pts.size < 3 || maxOf(maxX - minX, maxY - minY) < 30) {
            val s = "지나온 길이 짧아 그리지 않았어요"
            cv.drawText(s, (width - note.measureText(s)) / 2, (t + b) / 2, note); return
        }
        val k = minOf((r - l) / (maxX - minX).coerceAtLeast(1.0), (b - t) / (maxY - minY).coerceAtLeast(1.0)).toFloat()
        val ox = (l + r) / 2 - ((minX + maxX) / 2).toFloat() * k
        val oy = (t + b) / 2 + ((minY + maxY) / 2).toFloat() * k   // 북쪽이 위
        fun sx(p: RoutePoint) = ox + p.x.toFloat() * k
        fun sy(p: RoutePoint) = oy - p.y.toFloat() * k

        // 편안한 구간부터 그려서 흔들린 구간이 위에 오게
        for (level in Risk.entries) {
            line.color = ink(level); line.strokeWidth = width(level)
            for (i in 1 until pts.size) {
                val p = pts[i]
                if (p.risk == level) cv.drawLine(sx(pts[i - 1]), sy(pts[i - 1]), sx(p), sy(p), line)
            }
        }
        // 출발(빈 원) · 도착(찬 원)
        ring.color = T.TEXT; ring.strokeWidth = 3 * d; dot.color = T.SURFACE
        cv.drawCircle(sx(pts.first()), sy(pts.first()), 8 * d, dot); cv.drawCircle(sx(pts.first()), sy(pts.first()), 8 * d, ring)
        dot.color = T.TEXT; cv.drawCircle(sx(pts.last()), sy(pts.last()), 9 * d, dot)
        // 케어 시작 지점: 같은 케어가 여러 번이면 이름은 처음 한 번만
        name.color = T.ACCENT; name.textSize = T.SMALL * sp
        val named = HashSet<Care>(); val taken = ArrayList<RectF>()
        for ((i, care) in route.marks) {
            val p = pts.getOrNull(i) ?: continue
            val x = sx(p); val y = sy(p); val rad = 16 * d
            dot.color = T.ACCENT; cv.drawCircle(x, y, rad, dot)
            icon(care).apply { val s = (11 * d).toInt(); setBounds((x - s).toInt(), (y - s).toInt(), (x + s).toInt(), (y + s).toInt()); draw(cv) }
            if (!named.add(care)) continue
            val label = careName(care); val w = name.measureText(label)
            var lx = x + rad + 8 * d; if (lx + w > width - 8 * d) lx = x - rad - 8 * d - w
            val box = RectF(lx, y - 14 * d, lx + w, y + 10 * d)
            while (taken.any { RectF.intersects(it, box) }) box.offset(0f, 26 * d)
            taken += box
            // 선 위에 겹쳐도 읽히게 바탕색 테두리를 먼저 그림
            halo.color = T.SURFACE; halo.textSize = name.textSize; halo.typeface = name.typeface
            cv.drawText(label, box.left, box.bottom - 4 * d, halo)
            cv.drawText(label, box.left, box.bottom - 4 * d, name)
        }
    }

    private fun icon(c: Care) = icons.getOrPut(c) {
        context.getDrawable(careIcon(c))!!.mutate().apply { setTint(T.SURFACE) }
    }

    /** 아래 범례: 선 굵기·색 세 단계 + 케어 점 */
    private fun drawLegend(cv: Canvas, base: Float) {
        var x = 36 * d
        for (level in Risk.entries) {
            line.color = ink(level); line.strokeWidth = width(level)
            cv.drawLine(x, base - 6 * d, x + 28 * d, base - 6 * d, line)
            x += 38 * d
            val s = riskName(level); cv.drawText(s, x, base, note); x += note.measureText(s) + 28 * d
        }
        dot.color = T.ACCENT; cv.drawCircle(x + 8 * d, base - 6 * d, 8 * d, dot)
        cv.drawText("케어 시작", x + 24 * d, base, note)
    }
}
