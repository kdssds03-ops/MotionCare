package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortTest {

    private fun run(ticks: List<DemoTick>) = Comfort().apply {
        ticks.forEach { update(it.speed, it.heading, 0.5) }
    }

    @Test fun roughDemoReachesHighRiskThenRecovers() {
        val c = Comfort()
        var last = Risk.LOW
        demoDrive().forEach { last = c.update(it.speed, it.heading, 0.5) }
        assertEquals(Risk.HIGH, c.maxRisk)
        assertTrue("데모 끝에는 위험 단계에서 내려와야 함", last < Risk.HIGH)
        assertTrue("score=${c.score()}", c.score() in 20..60)
    }

    @Test fun steadyCruiseStaysLow() {
        val c = run(List(480) { DemoTick(14.0, 90.0, 0) })
        assertEquals(Risk.LOW, c.maxRisk)
        assertEquals(0, c.harshEvents)
        assertTrue(c.score() >= 95)
    }

    /** 가다 서다 2분 뒤 일정 속도 5분: 지수가 실제로 내려가는지, 얼마나 빨리 내려가는지 */
    @Test fun riskFallsAfterRoughDrivingStops() {
        val c = Comfort()
        val idx = ArrayList<Double>()
        var t = 0.0
        while (t < 420) {
            val speed = if (t < 120) 9.0 + 4.0 * kotlin.math.sin(2 * Math.PI * t / 8) else 9.0
            c.update(speed, 90.0, 0.5); idx += c.windowMsdv; t += 0.5
        }
        val at = { s: Int -> idx[s * 2 - 1] }
        println("지수: 1분 %.1f, 2분(최고) %.1f, 3분 %.1f, 4분 %.1f, 5분 %.1f, 7분 %.1f"
            .format(at(60), at(120), at(180), at(240), at(300), at(420)))
        assertTrue(at(180) < at(120))
        assertTrue(at(420) < Comfort.CAUTION_MSDV)
    }

    /** GPS 진행 방향이 고정이어도 핸들을 좌우로 돌리면 커브 흔들림으로 잡혀야 함 */
    @Test fun steeringAloneDetectsCurves() {
        val c = Comfort()
        var t = 0.0
        while (t < 120) {
            val steer = 45.0 * kotlin.math.sin(2 * Math.PI * t / 6)   // 6초 주기 S자 커브
            c.update(15.0, 0.0, 0.5, steer); t += 0.5
        }
        assertTrue("maxRisk=${c.maxRisk}", c.maxRisk >= Risk.CAUTION)
        val straight = Comfort().apply { repeat(240) { update(15.0, 0.0, 0.5, 0.0) } }
        assertEquals(Risk.LOW, straight.maxRisk)
    }

    @Test fun headingWrapsAcrossNorth() {
        assertEquals(-2.0, Comfort.wrapDeg(358.0 - 0.0), 1e-9)
        assertEquals(2.0, Comfort.wrapDeg(1.0 - 359.0), 1e-9)
    }

    @Test fun missedSicknessRaisesSensitivity() {
        val c = run(List(480) { DemoTick(14.0, 90.0, 0) })
        c.feedback(misc = 6)
        assertTrue(c.sensitivity > 1.0)
    }
}
