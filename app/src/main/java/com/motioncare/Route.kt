package com.motioncare

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 지나온 길의 한 점. 출발점 기준 미터 (x 동쪽, y 북쪽)와 그때의 흔들림 단계 */
data class RoutePoint(val x: Double, val y: Double, val risk: Risk)

/**
 * 속도와 진행 방향을 쌓아 지나온 길의 모양을 그린다 (지도 아님, GPS 좌표도 안 씀).
 * 데모는 가상 주행의 진행 방향, 실제 주행은 FusedLocation 진행 방향을 쓴다. 방향을 모르면 직전 방향 유지.
 */
class RouteTrace {
    private val pts = mutableListOf(RoutePoint(0.0, 0.0, Risk.LOW))
    private val mk = mutableListOf<Pair<Int, Care>>()
    private var x = 0.0; private var y = 0.0; private var heading = 0.0
    private var step = STEP_M   // 점 간격. 솎아낼 때마다 두 배로 해서 길 전체가 고른 간격을 유지

    val points: List<RoutePoint> get() = pts
    /** (점 번호, 케어): 그 케어가 시작된 곳 */
    val marks: List<Pair<Int, Care>> get() = mk

    fun add(speed: Double, headingDeg: Double?, dt: Double, risk: Risk) {
        if (headingDeg != null) heading = headingDeg
        val d = speed * dt
        val h = Math.toRadians(heading)
        x += sin(h) * d; y += cos(h) * d
        val last = pts.last()
        if (hypot(x - last.x, y - last.y) >= step) pts += RoutePoint(x, y, risk)
        if (pts.size > MAX_POINTS) halve()
    }

    fun mark(care: Care) { mk += pts.lastIndex to care }

    /** 점이 너무 많으면 둘 중 하나만 남김. 남는 점은 둘 중 더 흔들린 단계를 가져서 흔들린 구간이 사라지지 않게 */
    private fun halve() {
        val kept = pts.indices.step(2).map { i -> pts[i].copy(risk = maxOf(pts[i].risk, pts.getOrNull(i + 1)?.risk ?: Risk.LOW)) }
        pts.clear(); pts += kept
        step *= 2
        for (j in mk.indices) mk[j] = mk[j].first / 2 to mk[j].second
    }

    companion object {
        const val STEP_M = 5.0
        const val MAX_POINTS = 600
    }
}
