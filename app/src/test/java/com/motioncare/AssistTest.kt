package com.motioncare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistTest {

    @Test fun sickPhrases() {
        assertTrue(isSick("속이 안 좋아"))
        assertTrue(isSick("멀미 나요"))
        assertTrue(isSick("어지러워"))
        assertFalse(isSick("음악 틀어줘"))
        assertFalse(isSick("속도 좀 줄여줘"))   // "속"만으로 판단하면 속도에 걸림
        assertFalse(isSick(""))
    }

    @Test fun answers() {
        assertEquals(true, answer("네 추가해줘"))
        assertEquals(false, answer("아니 괜찮아"))
        assertEquals(true, answer("응"))
        assertNull(answer("어?"))
    }

    private val a = RestArea("A", 37.40, 127.10)
    private val b = RestArea("B", 36.00, 127.50)

    @Test fun nearestPicksClosest() {
        val (area, km) = nearestRestArea(37.5665, 126.9780, listOf(b, a))!!
        assertEquals("A", area.name)
        assertTrue("km=$km", km in 15.0..25.0)
    }

    @Test fun nothingWithin50Km() = assertNull(nearestRestArea(37.42, -122.08, listOf(a, b)))

    @Test fun parsesRestAreas() {
        assertEquals(listOf(RestArea("죽전", 37.33, 127.10), RestArea("기흥", 37.25, 127.11)),
            parseRestAreas("""[{"n":"죽전","a":37.33,"o":127.10},{"n":"기흥","a":37.25,"o":127.11}]"""))
        assertEquals(emptyList<RestArea>(), parseRestAreas("{oops"))
    }

    @Test fun turnCueOncePerManeuver() {
        assertTrue(shouldCueTurn(250.0, "L-용인", null))
        assertFalse(shouldCueTurn(240.0, "L-용인", "L-용인"))
        assertFalse(shouldCueTurn(400.0, "R-수원", "L-용인"))
        assertFalse(shouldCueTurn(20.0, "R-수원", "L-용인"))
    }

    @Test fun speechGate() {
        val g = SpeechGate()
        assertTrue(g.allow("곧 왼쪽", 0))
        assertFalse(g.allow("곧 왼쪽", 10_000))
        assertTrue(g.allow("곧 오른쪽", 11_000))
        assertTrue(g.allow("곧 왼쪽", 31_000))
    }
}
