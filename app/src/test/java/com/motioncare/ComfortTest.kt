package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun comfortSnapshotRestores() {
        val a = Comfort(1.3)
        demoDrive().take(240).forEach { a.update(it.speed, it.heading, 0.5) }   // 120초
        val b = Comfort()
        b.restore(a.snapshot())
        assertEquals(a.windowMsdv, b.windowMsdv, 1e-9)
        assertEquals(a.score(), b.score())
        assertEquals(a.harshEvents, b.harshEvents)
        assertEquals(a.maxRisk, b.maxRisk)
        assertEquals(1.3, b.sensitivity, 1e-9)
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

    private fun drive(c: DemoCourse, s: Double = 1.0) = Comfort(s).also { m -> demoDrive(0.5, c).forEach { m.update(it.speed, it.heading, 0.5) } }

    @Test fun demoCoursesGiveDifferentScoresByDrivingStyle() {
        val smooth = drive(DemoCourse.SMOOTH); val winding = drive(DemoCourse.WINDING); val city = drive(DemoCourse.CITY)
        assertTrue("${smooth.score()} > ${winding.score()} > ${city.score()}", smooth.score() > winding.score() && winding.score() > city.score())
        assertEquals(Risk.LOW, smooth.maxRisk)          // 케어가 필요 없음
        assertEquals(Risk.HIGH, winding.maxRisk)        // 계속 이어지는 커브: 호흡 가이드까지
        assertEquals(Risk.HIGH, city.maxRisk)           // 호흡 가이드까지
        // 세 코스 모두 같은 길이 (6분 20초)
        assertEquals(smooth.durationS, city.durationS, 0.01); assertEquals(winding.durationS, city.durationS, 0.01)
    }

    @Test fun scoreRatesTheRideNotThePassenger() {
        // 민감도는 경고·케어 시점만 바꾸고, 편안함 점수(주행이 얼마나 부드러웠나)는 그대로
        for (c in DemoCourse.entries) assertEquals(drive(c, 0.8).score(), drive(c, 1.3).score())
        assertEquals(Risk.HIGH, drive(DemoCourse.WINDING, 1.3).maxRisk)   // 민감하면 같은 길에서 더 일찍 호흡 가이드
    }

    @Test fun demoRunsVaryButKeepEachCourseCharacter() {
        fun runs(c: DemoCourse) = (1L..100L).map { seed ->
            Comfort().also { m -> demoDrive(0.5, c, kotlin.random.Random(seed)).forEach { m.update(it.speed, it.heading, 0.5) } }
        }
        val smooth = runs(DemoCourse.SMOOTH); val winding = runs(DemoCourse.WINDING); val city = runs(DemoCourse.CITY)
        // 매번 조금씩 다름
        for (r in listOf(smooth, winding, city)) assertTrue(r.map { it.score() }.distinct().size >= 3)
        // 그래도 코스 성격은 그대로: 부드러운 운전은 케어 없이 높은 점수, 산길과 시내는 호흡 가이드까지
        assertTrue(smooth.all { it.maxRisk == Risk.LOW && it.score() >= 80 })
        assertTrue(winding.all { it.maxRisk == Risk.HIGH && it.score() in 55..75 })
        assertTrue(city.all { it.maxRisk == Risk.HIGH && it.score() <= 60 })
    }

    /** 굽은 길의 좌우 흔들림(커브 하나 10초 이상)도 멀미 원인으로 충분히 잡혀야 함 (Donohew & Griffin 2004) */
    @Test fun slowLateralSwayCounts() {
        val c = Comfort()
        var heading = 0.0; var t = 0.0
        while (t < 300) {   // 20초 주기로 좌우로 이어지는 커브, 시속 43km
            heading += Math.toDegrees(0.25 * 2 * Math.PI / 20 * kotlin.math.cos(2 * Math.PI * t / 20)) * 0.5
            c.update(12.0, (heading + 360) % 360, 0.5); t += 0.5
        }
        assertTrue("maxRisk=${c.maxRisk}", c.maxRisk >= Risk.CAUTION)
    }

    /** 점수는 흔들림 양이 주로 정하고, 급조작 횟수는 감점의 절반을 넘지 않음 */
    @Test fun swayNotHarshCountDrivesScore() {
        val city = drive(DemoCourse.CITY)
        val penalty = 100 - city.score()
        assertTrue("harsh ${Comfort.SCORE_PER_EVENT * city.harshEvents} of $penalty", Comfort.SCORE_PER_EVENT * city.harshEvents < penalty / 2.0)
        assertTrue("smooth ${drive(DemoCourse.SMOOTH).score()} vs winding ${drive(DemoCourse.WINDING).score()}",
            drive(DemoCourse.SMOOTH).score() - drive(DemoCourse.WINDING).score() >= 20)
    }

    /** "나아지고 있어요"는 주의 단계에서 20초 사이 10% 넘게 내려갈 때만 (가다 서다의 잠깐 떨어짐은 제외) */
    @Test fun easingOnlyOnRealDropAtCaution() {
        assertFalse(isEasing(14.6, 15.0, Risk.HIGH))     // 아직 많이 흔들림
        assertTrue(isEasing(8.0, 9.5, Risk.CAUTION))
        assertFalse(isEasing(8.0, 8.3, Risk.CAUTION))    // 오르내리는 정도
        assertFalse(isEasing(3.0, 5.0, Risk.LOW))        // 이미 편안함
    }
}
