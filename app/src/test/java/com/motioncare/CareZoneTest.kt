package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareZoneTest {
    // 좌석: 1 운전석, 2 동승석, 3 뒷좌석 왼쪽, 4 뒷좌석 오른쪽
    private fun zones(areas: List<Set<Int>>, seat: Int) = careZones(areas, { it }, seat, fallback = 2)

    @Test fun onlyPassengerZone() =
        assertEquals(listOf(setOf(2)), zones(listOf(setOf(1), setOf(2), setOf(3, 4)), 2))

    @Test fun rearPassengerGetsRearZone() =
        assertEquals(listOf(setOf(3, 4)), zones(listOf(setOf(1), setOf(2), setOf(3, 4)), 4))

    @Test fun rearWithoutOwnZoneFollowsFrontPassenger() =
        assertEquals(listOf(setOf(2)), zones(listOf(setOf(1), setOf(2)), 4))

    @Test fun singleCabinZoneStillWorks() =
        assertEquals(listOf(setOf(1, 2, 3, 4)), zones(listOf(setOf(1, 2, 3, 4)), 2))

    @Test fun neverPicksDriverOnlyZone() =
        assertFalse(zones(listOf(setOf(1), setOf(2)), 3).contains(setOf(1)))

    @Test fun restoresOriginalTemp() = assertEquals(23f, restoreTemp(23f, 21f, 21f))
    @Test fun keepsTempTheRiderChose() = assertNull(restoreTemp(23f, 21f, 19.5f))
    @Test fun noRestoreWhenAppNeverChangedTemp() = assertNull(restoreTemp(23f, null, 23f))
    @Test fun restoresWhenCurrentUnknown() = assertEquals(23f, restoreTemp(23f, 21f, null))

    @Test fun airCareOnceThenWaits() {
        assertTrue(autoAirAgain(null, 30.0))
        assertFalse(autoAirAgain(30.0, 200.0))
        assertTrue(autoAirAgain(30.0, 30.0 + AIR_AGAIN_S))
    }

    @Test fun sameNamedTurnAfterPassingIsNew() {
        assertNull(nextTurnKey("L-시청로", "L-시청로", lastDistM = 20.0, distM = 300.0))
        assertEquals("L-시청로", nextTurnKey("L-시청로", "L-시청로", lastDistM = 80.0, distM = 60.0))
        assertEquals("R-a", nextTurnKey("R-a", "L-b", lastDistM = 20.0, distM = 300.0))
    }
}
