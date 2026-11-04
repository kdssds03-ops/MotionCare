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
        repeat(3) { p = p.learn(trip(5, cares = setOf(Care.AIR))) }
        assertTrue(p.cares.getValue(Care.AIR).off)

        var q = base
        q = q.learn(trip(5, cares = setOf(Care.AIR), helped = setOf(Care.AIR)))
        repeat(2) { q = q.learn(trip(5, cares = setOf(Care.AIR))) }
        assertFalse(q.cares.getValue(Care.AIR).off)
    }

    @Test fun noSicknessTripsDoNotTurnCareOff() {
        var p = base
        repeat(5) { p = p.learn(trip(0, cares = setOf(Care.TURN_CUE))) }
        assertFalse(p.cares[Care.TURN_CUE]?.off == true)
        assertEquals(0, p.cares[Care.TURN_CUE]?.runs ?: 0)
    }

    @Test fun unansweredTripsDoNotCount() {
        var p = base
        repeat(5) { p = p.learn(trip(null, cares = setOf(Care.AIR))) }
        assertEquals(0, p.cares[Care.AIR]?.runs ?: 0)
    }

    @Test fun careTargetLowersOneToTwo() {
        assertEquals(22f, careTarget(24f, 21f)!!, 1e-6f)     // 선호가 너무 낮으면 -2까지만
        assertEquals(23f, careTarget(24f, 23.5f)!!, 1e-6f)   // 선호가 너무 높으면 -1
        assertEquals(21.5f, careTarget(23f, 21.5f)!!, 1e-6f) // 범위 안이면 선호 그대로
    }

    @Test fun neverWarmer() {
        assertEquals(18f, careTarget(19f, 21f)!!, 1e-6f)
        assertEquals(17f, careTarget(17f, 21f)!!, 1e-6f)     // 바닥 18보다 추운 차도 데우지 않음
    }

    @Test fun unknownCurrentLeavesTemperature() = assertEquals(null, careTarget(null, 21f))

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

    @Test fun learningLineSaysEarlierOrLater() {
        val p = Profile("a", "지우", Seat.FRONT_RIGHT, 1.0)
        assertEquals("다음부터 경고가 10% 일찍 와요", learningLine(p, p.copy(sensitivity = 1.1)))
        assertEquals("다음부터 경고가 10% 늦게 와요", learningLine(p, p.copy(sensitivity = 0.9)))
        assertEquals("경고 시점은 그대로예요", learningLine(p, p))
    }

    @Test fun learningLineMentionsCareTurnedOff() {
        val p = Profile("a", "지우", Seat.FRONT_RIGHT, 1.0, cares = mapOf(Care.BREATH to CareStat(2, 0)))
        val after = p.copy(cares = mapOf(Care.BREATH to CareStat(3, 0, off = true)))
        assertEquals("경고 시점은 그대로예요 · 효과가 적었던 호흡 가이드는 꺼 둘게요", learningLine(p, after))
    }

    @Test fun disableCareKeepsStats() {
        val p = Profile("a", "지우", Seat.FRONT_RIGHT, 1.0, cares = mapOf(Care.AIR to CareStat(4, 3)))
        assertEquals(CareStat(4, 3, off = true), p.disableCare(Care.AIR).cares[Care.AIR])
        assertEquals(CareStat(0, 0, off = true), p.disableCare(Care.RECLINE).cares[Care.RECLINE])
    }

    @Test fun tripsUnderOneMinuteAreNotKept() {
        assertFalse(worthKeeping(trip(null).copy(minutes = 0)))
        assertTrue(worthKeeping(trip(null).copy(minutes = 1)))
    }

    @Test fun underOneMinuteIsNeverKeptWithPlaceholderScore() {
        // 점수는 1분 미만이면 100(판단 안 함): 그런 주행이 기록으로 남으면 안 됨
        val short = Comfort().apply { repeat(90) { update(30.0, 0.0, 0.5) } }   // 45초
        assertEquals(100, short.score())
        assertEquals(0, tripMinutes(short.durationS))
        assertFalse(worthKeeping(trip(null).copy(minutes = tripMinutes(short.durationS), score = short.score())))
        assertEquals(1, tripMinutes(60.0))
        assertEquals(1, tripMinutes(119.0))
    }

    @Test fun corruptFileIsKeptAsBackupNotOverwritten() {
        java.io.File(tmp.root, "profiles.json").writeText("[{oops")
        val store = ProfileStore(tmp.root)
        assertEquals(emptyList<Profile>(), store.load())
        store.save(listOf(base))
        assertEquals("[{oops", java.io.File(tmp.root, "profiles.json.bad").readText())
    }
}
