package com.motioncare

import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// "속이 안 좋아요" 즉시 케어, 휴게소, 회전 예고를 판단하는 순수 로직 (화면·SDK와 분리해서 테스트)

private val SICK = listOf("속이", "속 안", "멀미", "어지러", "토할", "메스꺼", "울렁")
private val NO = listOf("아니", "됐어", "괜찮아", "취소")
private val YES = listOf("예", "네", "응", "좋아", "추가")

fun isSick(text: String): Boolean = SICK.any { it in text }

/** 예 → true, 아니요 → false, 모르겠음 → null. "아니 괜찮아"처럼 섞이면 아니요가 이긴다. */
fun answer(text: String): Boolean? = when {
    NO.any { it in text } -> false
    YES.any { it in text } -> true
    else -> null
}

data class RestArea(val name: String, val lat: Double, val lon: Double)

/** 형식: [{"n":"이름","a":위도,"o":경도}] — 잘못된 데이터면 빈 목록 */
fun parseRestAreas(json: String): List<RestArea> = runCatching {
    val a = JSONArray(json)
    List(a.length()) { a.getJSONObject(it).let { o -> RestArea(o.getString("n"), o.getDouble("a"), o.getDouble("o")) } }
}.getOrDefault(emptyList())

fun nearestRestArea(lat: Double, lon: Double, areas: List<RestArea>, maxKm: Double = 50.0): Pair<RestArea, Double>? =
    areas.map { it to km(lat, lon, it.lat, it.lon) }.filter { it.second <= maxKm }.minByOrNull { it.second }

private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math::toRadians
    val h = sin((r(lat2) - r(lat1)) / 2).pow(2) + cos(r(lat1)) * cos(r(lat2)) * sin((r(lon2) - r(lon1)) / 2).pow(2)
    return 2 * 6371.0 * asin(sqrt(h))
}

/** 다음 회전까지 300m 안(30m보다 멀 때)에 들어오면 그 회전에 한 번만 예고 */
fun shouldCueTurn(distanceM: Double, key: String, lastKey: String?): Boolean =
    distanceM > 30 && distanceM <= 300 && key != lastKey

/** 같은 안내를 gapMs 안에 반복하지 않는다 */
class SpeechGate(private val gapMs: Long = 30_000) {
    private val last = HashMap<String, Long>()
    fun allow(text: String, nowMs: Long): Boolean {
        val t = last[text]
        if (t != null && nowMs - t < gapMs) return false
        last[text] = nowMs
        return true
    }
}
