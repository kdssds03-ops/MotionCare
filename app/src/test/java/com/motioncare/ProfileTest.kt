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

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun roundTrip() {
        val store = ProfileStore(tmp.root)
        val a = base.copy(careTempC = 22.5f, cares = mapOf(Care.AIR to CareStat(3, 1, false), Care.BREATH to CareStat(4, 0, true)),
            trips = listOf(trip(6, Risk.HIGH, setOf(Care.AIR, Care.BREATH), setOf(Care.AIR), start = 123).copy(demo = true), trip(null, start = 5)))
        val b = Profile(id = "p2", name = "엄마", seat = Seat.FRONT_RIGHT, sensitivity = 1.3)
        store.save(listOf(a, b))
        assertEquals(listOf(a, b), ProfileStore(tmp.root).load())
    }

    @Test fun corruptFileGivesEmpty() {
        java.io.File(tmp.root, "profiles.json").writeText("{oops")
        assertEquals(emptyList<Profile>(), ProfileStore(tmp.root).load())
    }

    @Test fun missingFileGivesEmpty() = assertEquals(emptyList<Profile>(), ProfileStore(tmp.root).load())

    @Test fun atomicWriteLeavesNoTemp() {
        ProfileStore(tmp.root).save(listOf(base))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun guestOngoingNotPersisted() {
        val store = ProfileStore(tmp.root)
        store.saveOngoing(Ongoing("p1", demo = false, startMs = 1, comfort = mapOf("durationS" to 60.0), cares = setOf(Care.AIR)))
        store.saveOngoing(Ongoing(null, demo = false, startMs = 2, comfort = emptyMap(), cares = emptySet()))
        assertEquals(null, store.loadOngoing())
        assertFalse(java.io.File(tmp.root, "ongoing.json").exists())
    }

    @Test fun ongoingRoundTrip() {
        val o = Ongoing("p1", demo = true, startMs = 42, comfort = mapOf("durationS" to 61.5, "maxRisk" to 2.0), cares = setOf(Care.BREATH))
        val store = ProfileStore(tmp.root)
        store.saveOngoing(o)
        assertEquals(o, store.loadOngoing())
    }

    @Test fun earlierPercent() {
        assertEquals(20, base.copy(sensitivity = 1.2).earlierPercent())
        assertEquals(-10, base.copy(sensitivity = 0.9).earlierPercent())
    }
}
