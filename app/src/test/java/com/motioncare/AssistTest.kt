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

    @Test fun bundledDataParses() {
        val areas = parseRestAreas(java.io.File("src/main/res/raw/rest_areas.json").readText())
        assertTrue("count=${areas.size}", areas.size >= 100)
        assertTrue(areas.all { it.lat in 33.0..39.0 && it.lon in 124.0..132.0 && it.name.isNotBlank() })
    }

    @Test fun cueAboutFourSecondsBefore() {
        assertFalse(shouldCueTurn(100.0, 14.0, "k", null))   // 7초 전
        assertTrue(shouldCueTurn(50.0, 14.0, "k", null))     // 3.6초 전
        assertFalse(shouldCueTurn(50.0, 14.0, "k", "k"))     // 같은 회전은 한 번
        assertFalse(shouldCueTurn(3.0, 14.0, "k", null))     // 이미 회전 중
    }

    @Test fun stoppedUsesDistanceOnly() {
        assertFalse(shouldCueTurn(100.0, 0.0, "k", null))
        assertTrue(shouldCueTurn(30.0, 0.0, "k", null))
    }

    @Test fun speechGate() {
        val g = SpeechGate()
        assertTrue(g.allow("곧 왼쪽", 0))
        assertFalse(g.allow("곧 왼쪽", 10_000))
        assertTrue(g.allow("곧 오른쪽", 11_000))
        assertTrue(g.allow("곧 왼쪽", 31_000))
    }

    @Test fun turnSideFromNaviType() {
        assertEquals(-1, turnSide("TURN_LEFT")); assertEquals(-1, turnSide("TURN_SHARP_L")); assertEquals(-1, turnSide("TURN_UTURN"))
        assertEquals(1, turnSide("TURN_RIGHT")); assertEquals(1, turnSide("TURN_SHARP_R"))
        assertEquals(0, turnSide("TURN_STRAIGHT")); assertEquals(0, turnSide("TURN_KEEP_L")); assertEquals(0, turnSide("TURN_TOLL"))
    }

    @Test fun turnCueTextIsShort() {
        assertEquals("곧 왼쪽", turnCueText(-1))
        assertEquals("곧 오른쪽", turnCueText(1))
    }

    @Test fun userTempOnlyWhenDifferentFromSent() {
        assertFalse(isUserTemp(null, 23f))
        assertFalse(isUserTemp(21f, 21.05f))
        assertTrue(isUserTemp(21f, 23f))
    }

    @Test fun notFeelingGoodIsNotYes() {
        assertEquals(false, answer("속이 안 좋아"))
        assertEquals(false, answer("안 좋아요"))
        assertEquals(true, answer("좋아 추가해 줘"))
    }
}
