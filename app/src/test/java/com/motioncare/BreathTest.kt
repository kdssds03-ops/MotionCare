package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathTest {
    @Test fun extendsWhileRiskStays() =
        assertEquals(350_000L, breathUntil(0, 300_000, 290_000, Risk.CAUTION))

    @Test fun neverBeyondMax() =
        assertEquals(900_000L, breathUntil(0, 890_000, 880_000, Risk.HIGH))

    @Test fun lowRiskDoesNotExtend() =
        assertEquals(300_000L, breathUntil(0, 300_000, 290_000, Risk.LOW))

    @Test fun stoppedBreathIsNotExtended() =   // 사용자가 멈추면 untilMs = 0
        assertEquals(0L, breathUntil(0, 0, 100_000, Risk.HIGH))

    @Test fun phasesAlternateEvery4s() {
        assertTrue(isInhale(0)); assertTrue(isInhale(3_999))
        assertFalse(isInhale(4_000)); assertTrue(isInhale(8_000))
    }

    @Test fun matchesBreathCircle() {   // 조명·소리와 화면 원이 같은 박자
        for (e in listOf(0L, 3_999L, 4_000L, 7_999L, 8_000L, 123_456L)) assertEquals(breathPhase(0, e).second, isInhale(e))
    }

    @Test fun autoStartsOnRiseToHigh() {
        assertTrue(autoStartBreath(Risk.HIGH, Risk.CAUTION, userStopped = false))
        assertFalse(autoStartBreath(Risk.HIGH, Risk.HIGH, userStopped = false))
    }

    @Test fun userStopSurvivesHighFlicker() =   // 직접 멈춘 뒤 주의↔위험을 오가도 다시 켜지지 않음
        assertFalse(autoStartBreath(Risk.HIGH, Risk.CAUTION, userStopped = true))

    @Test fun userStopClearsOnlyWhenCalm() {
        assertTrue(keepUserStop(Risk.CAUTION, userStopped = true))
        assertFalse(keepUserStop(Risk.LOW, userStopped = true))
    }
}
