package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private fun trip(misc: Int?, maxRisk: Risk = Risk.CAUTION, cares: Set<Care> = emptySet(), helped: Set<Care> = emptySet(), start: Long = 0) =
        Trip(startMs = start, minutes = 10, score = 70, maxRisk = maxRisk, misc = misc, cares = cares, helped = helped, demo = false)

    private val base = Profile(id = "p1", name = "지우", seat = Seat.REAR_LEFT, sensitivity = 1.0)

    @Test fun surveyMapsToSensitivity() {
        assertEquals(0.8, surveySensitivity(listOf(0, 0, 1)), 1e-9)
        assertEquals(1.0, surveySensitivity(listOf(1, 1, 0)), 1e-9)
        assertEquals(1.3, surveySensitivity(listOf(2, 1, 2)), 1e-9)
    }

    @Test fun sickWithoutHighMakesEarlier() = assertEquals(1.1, adjustSensitivity(1.0, 6, Risk.CAUTION), 1e-9)

    @Test fun calmButHighMakesLater() = assertEquals(0.9, adjustSensitivity(1.0, 0, Risk.HIGH), 1e-9)

    @Test fun sensitivityClamped() {
        assertEquals(2.0, adjustSensitivity(1.95, 9, Risk.LOW), 1e-9)
        assertEquals(0.5, adjustSensitivity(0.52, 0, Risk.HIGH), 1e-9)
    }

    @Test fun learnAddsTripAndKeeps50() {
        val full = base.copy(trips = List(50) { trip(null, start = it.toLong()) })
        val next = full.learn(trip(null, start = 999))
        assertEquals(50, next.trips.size)
        assertEquals(999L, next.trips.first().startMs)
    }

    @Test fun unhelpfulCareTurnsOff() {
        var p = base
        repeat(3) { p = p.learn(trip(null, cares = setOf(Care.AIR))) }
        assertTrue(p.cares.getValue(Care.AIR).off)

        var q = base
        q = q.learn(trip(null, cares = setOf(Care.AIR), helped = setOf(Care.AIR)))
        repeat(2) { q = q.learn(trip(null, cares = setOf(Care.AIR))) }
        assertFalse(q.cares.getValue(Care.AIR).off)
    }

    @Test fun reEnableResetsStats() {
        val off = base.copy(cares = mapOf(Care.AIR to CareStat(runs = 3, helped = 0, off = true)))
        assertEquals(CareStat(), off.enableCare(Care.AIR).cares.getValue(Care.AIR))
    }

    @Test fun miscNullDoesNotChangeSensitivity() =
        assertEquals(1.0, base.learn(trip(null, maxRisk = Risk.HIGH)).sensitivity, 1e-9)

    @Test fun tempClamped() {
        assertEquals(18f, learnTemp(16f))
        assertEquals(26f, learnTemp(30f))
        assertEquals(22.5f, learnTemp(22.5f))
    }

    @Test fun earlierPercent() {
        assertEquals(20, base.copy(sensitivity = 1.2).earlierPercent())
        assertEquals(-10, base.copy(sensitivity = 0.9).earlierPercent())
    }
}
