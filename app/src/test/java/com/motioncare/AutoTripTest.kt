package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTripTest {

    /** 같은 상태로 seconds초 동안 0.5초마다 갱신하고, 마지막이 아닌 첫 판단(START/END)을 돌려줌 */
    private fun AutoTrip.run(seconds: Double, speed: Double, parked: Boolean?, tripOn: Boolean): AutoTripAction {
        var t = 0.0
        while (t < seconds) {
            val a = update(0.5, speed, parked, tripOn)
            if (a != AutoTripAction.NONE) return a
            t += 0.5
        }
        return AutoTripAction.NONE
    }

    @Test fun startsAfterMovingOutOfPark() =
        assertEquals(AutoTripAction.START, AutoTrip().run(6.0, 5.0, parked = false, tripOn = false))

    @Test fun shortRollDoesNotStart() =
        assertEquals(AutoTripAction.NONE, AutoTrip().run(3.0, 5.0, parked = false, tripOn = false))

    @Test fun stoppingResetsStartTimer() {
        val a = AutoTrip()
        a.run(4.0, 5.0, false, false)
        a.run(1.0, 0.0, false, false)   // 잠깐 멈춤
        assertEquals(AutoTripAction.NONE, a.run(4.0, 5.0, false, false))
    }

    @Test fun neverStartsInPark() =
        assertEquals(AutoTripAction.NONE, AutoTrip().run(30.0, 5.0, parked = true, tripOn = false))

    @Test fun unknownGearStartsOnSpeedAlone() =
        assertEquals(AutoTripAction.START, AutoTrip().run(6.0, 5.0, parked = null, tripOn = false))

    @Test fun slowCreepDoesNotStart() =
        assertEquals(AutoTripAction.NONE, AutoTrip().run(30.0, 1.0, parked = false, tripOn = false))

    @Test fun endsAfterStayingInPark() {
        val a = AutoTrip()
        assertEquals(AutoTripAction.NONE, a.run(AutoTrip.END_AFTER_S - 1, 0.0, parked = true, tripOn = true))
        assertEquals(AutoTripAction.END, a.run(2.0, 0.0, parked = true, tripOn = true))
    }

    @Test fun redLightInDriveDoesNotEnd() =
        assertEquals(AutoTripAction.NONE, AutoTrip().run(600.0, 0.0, parked = false, tripOn = true))

    @Test fun unknownGearNeverEnds() =
        assertEquals(AutoTripAction.NONE, AutoTrip().run(600.0, 0.0, parked = null, tripOn = true))

    @Test fun leavingParkResetsEndTimer() {
        val a = AutoTrip()
        a.run(AutoTrip.END_AFTER_S - 10, 0.0, true, true)
        a.run(1.0, 3.0, false, true)    // 다시 출발
        assertEquals(AutoTripAction.NONE, a.run(AutoTrip.END_AFTER_S - 10, 0.0, true, true))
    }

    @Test fun startFiresOnceThenWaitsForTrip() {
        val a = AutoTrip()
        assertEquals(AutoTripAction.START, a.run(6.0, 5.0, false, false))
        // 시작 판단 직후 같은 상태가 이어져도 다시 START를 내지 않고, 다음 주행을 위해 다시 5초를 셈
        assertEquals(AutoTripAction.NONE, a.update(0.5, 5.0, false, false))
    }
}
