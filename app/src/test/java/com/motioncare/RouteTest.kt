package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private fun drive(r: RouteTrace, ticks: Int, speed: Double = 10.0, heading: Double? = 0.0, risk: Risk = Risk.LOW) =
        repeat(ticks) { r.add(speed, heading, 0.5, risk) }

    @Test fun drivingNorthGoesUp() {
        val r = RouteTrace(); drive(r, 20)   // 10 m/s × 10초 = 100 m
        assertEquals(0.0, r.points.last().x, 0.5)
        assertEquals(100.0, r.points.last().y, 0.5)
    }

    @Test fun drivingEastGoesRight() {
        val r = RouteTrace(); drive(r, 20, heading = 90.0)
        assertEquals(100.0, r.points.last().x, 0.5)
        assertEquals(0.0, r.points.last().y, 0.5)
    }

    @Test fun missingHeadingKeepsLastDirection() {
        val r = RouteTrace(); drive(r, 4, heading = 90.0); drive(r, 4, heading = null)
        assertEquals(40.0, r.points.last().x, 0.5)   // 8틱 × 5 m
        assertEquals(0.0, r.points.last().y, 0.5)
    }

    @Test fun standingStillAddsNoPoints() {
        val r = RouteTrace(); drive(r, 10, speed = 0.0)
        assertEquals(1, r.points.size)
    }

    @Test fun pointKeepsRisk() {
        val r = RouteTrace(); drive(r, 4); drive(r, 4, risk = Risk.CAUTION)
        assertEquals(Risk.CAUTION, r.points.last().risk)
        assertEquals(Risk.LOW, r.points[1].risk)
    }

    @Test fun markAttachesToLatestPoint() {
        val r = RouteTrace(); drive(r, 10); r.mark(Care.AIR)
        assertEquals(listOf(r.points.lastIndex to Care.AIR), r.marks)
    }

    @Test fun longTripStaysBoundedAndMarksFollow() {
        val r = RouteTrace()
        drive(r, 20); r.mark(Care.BREATH)          // 100 m 지점
        drive(r, 2000)                              // 10 km 더
        assertTrue(r.points.size <= RouteTrace.MAX_POINTS)
        assertEquals(10_100.0, r.points.last().y, 20.0)
        val (i, care) = r.marks.single()
        assertEquals(Care.BREATH, care)
        assertEquals(100.0, r.points[i].y, 40.0)
    }

    /** 데모 코스를 그렸을 때 폭 ÷ 길이: 직선이면 0에 가까움 */
    private fun spread(c: DemoCourse): Double {
        val r = RouteTrace(); demoDrive(0.5, c).forEach { r.add(it.speed, it.heading, 0.5, Risk.LOW) }
        val w = r.points.maxOf { it.x } - r.points.minOf { it.x }
        val h = r.points.maxOf { it.y } - r.points.minOf { it.y }
        return minOf(w, h) / maxOf(w, h)
    }

    @Test fun windingDemoLooksWinding() = assertTrue("spread ${spread(DemoCourse.WINDING)}", spread(DemoCourse.WINDING) >= 0.25)

}
