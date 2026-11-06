package com.motioncare

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 지나온 길의 한 점. 출발점 기준 미터 (x 동쪽, y 북쪽)와 그때의 흔들림 단계 */
data class RoutePoint(val x: Double, val y: Double, val risk: Risk)

/** 시간축 흔들림 한 칸: 칸 끝 시각(초), 칸 안에서 가장 높았던 흔들림 지수와 단계 */
data class ShakeSample(val t: Double, val v: Double, val risk: Risk)

/**
 * 속도와 진행 방향을 쌓아 지나온 길의 모양을 그린다 (지도 아님, GPS 좌표도 안 씀).
 * 데모는 가상 주행의 진행 방향, 실제 주행은 FusedLocation 진행 방향을 쓴다. 방향을 모르면 직전 방향 유지.
 * 같은 주행의 흔들림 지수도 시간 순서로 모아 리포트의 흔들림 흐름 그래프에 쓴다.
 */
class RouteTrace {
    private val pts = mutableListOf(RoutePoint(0.0, 0.0, Risk.LOW))
    private val mk = mutableListOf<Pair<Int, Care>>()
    private var x = 0.0; private var y = 0.0; private var heading = 0.0
    private var step = STEP_M   // 점 간격. 솎아낼 때마다 두 배로 해서 길 전체가 고른 간격을 유지

    private val tl = mutableListOf<ShakeSample>()
    private val careT = mutableListOf<Pair<Double, Care>>()
    private val secs = DoubleArray(Risk.entries.size)
    private var now = 0.0
    private var tStep = STEP_S
    private var bucketEnd = STEP_S
    private var bucketV = 0.0; private var bucketRisk = Risk.LOW

    val points: List<RoutePoint> get() = pts
    /** (점 번호, 케어): 그 케어가 시작된 곳 */
    val marks: List<Pair<Int, Care>> get() = mk
    /** 시간 순서 흔들림 지수 (최대 MAX_POINTS칸) */
    val timeline: List<ShakeSample> get() = tl
    /** (주행 시작부터 초, 케어): 그 케어가 시작된 때 */
    val careTimes: List<Pair<Double, Care>> get() = careT
    /** 지금까지 주행한 시간(초) */
    val elapsedS: Double get() = now
    /** 단계별로 머문 시간(초) */
    fun secondsAt(r: Risk) = secs[r.ordinal]

    /** v: 흔들림 지수 (민감도 반영, Comfort 기준선과 같은 단위). 모르면 0 */
    fun add(speed: Double, headingDeg: Double?, dt: Double, risk: Risk, v: Double = 0.0) {
        if (headingDeg != null) heading = headingDeg
        val d = speed * dt
        val h = Math.toRadians(heading)
        x += sin(h) * d; y += cos(h) * d
        val last = pts.last()
        if (hypot(x - last.x, y - last.y) >= step) pts += RoutePoint(x, y, risk)
        if (pts.size > MAX_POINTS) halve()

        now += dt; secs[risk.ordinal] += dt
        bucketV = maxOf(bucketV, v); bucketRisk = maxOf(bucketRisk, risk)
        if (now >= bucketEnd - 1e-9) {
            tl += ShakeSample(now, bucketV, bucketRisk)
            bucketV = 0.0; bucketRisk = Risk.LOW; bucketEnd += tStep
            if (tl.size > MAX_POINTS) halveTimeline()
        }
    }

    fun mark(care: Care) { mk += pts.lastIndex to care; careT += now to care }

    /** 점이 너무 많으면 둘 중 하나만 남김. 남는 점은 둘 중 더 흔들린 단계를 가져서 흔들린 구간이 사라지지 않게 */
    private fun halve() {
        val kept = pts.indices.step(2).map { i -> pts[i].copy(risk = maxOf(pts[i].risk, pts.getOrNull(i + 1)?.risk ?: Risk.LOW)) }
        pts.clear(); pts += kept
        step *= 2
        for (j in mk.indices) mk[j] = mk[j].first / 2 to mk[j].second
    }

    /** 시간 칸을 둘씩 합침: 뒤 칸의 끝 시각, 둘 중 높은 값을 남겨 최고치가 사라지지 않게 */
    private fun halveTimeline() {
        val kept = tl.indices.step(2).map { i ->
            val a = tl[i]; val b = tl.getOrNull(i + 1) ?: return@map a
            ShakeSample(b.t, maxOf(a.v, b.v), maxOf(a.risk, b.risk))
        }
        tl.clear(); tl += kept
        tStep *= 2
    }

    companion object {
        const val STEP_M = 5.0
        const val STEP_S = 2.0
        const val MAX_POINTS = 600
    }
}
