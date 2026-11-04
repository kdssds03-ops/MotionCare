package com.motioncare

import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// "속이 안 좋아요" 즉시 케어, 휴게소, 회전 예고를 판단하는 순수 로직 (화면·SDK와 분리해서 테스트)

private val SICK = listOf("속이", "속 안", "멀미", "어지러", "토할", "메스꺼", "울렁")
private val NO = listOf("아니", "됐어", "괜찮아", "취소", "안 좋", "안좋")   // "속이 안 좋아"가 "좋아"로 예가 되지 않게
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

/**
 * 회전 직전(약 4초 전)에 그 회전에 한 번만 예고. 움직임 예고는 시작 직전에 줄 때 몸이 대비할 수 있음 (Reuten 2024).
 * 거의 멈춰 있으면(1m/s 미만) 시간 대신 40m 안인지로 판단.
 */
fun shouldCueTurn(distanceM: Double, speedMps: Double, key: String, lastKey: String?): Boolean =
    key != lastKey && distanceM > 5 &&
        (distanceM <= 40 || (speedMps >= 1 && distanceM / speedMps <= 4.0))

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

/** 내비 회전 종류(TurnType 이름) → -1 왼쪽, 1 오른쪽, 0 예고 안 함. 차선 유지·IC 진출입은 흔들림이 작아 제외. */
fun turnSide(type: String): Int = when (type) {
    "TURN_LEFT", "TURN_SHARP_L", "TURN_UTURN" -> -1
    "TURN_RIGHT", "TURN_SHARP_R" -> 1
    else -> 0
}

/** 짧게: 긴 문장은 말하는 동안 회전이 시작돼 예고 시점이 흐려짐 */
fun turnCueText(side: Int): String = if (side < 0) "곧 왼쪽" else "곧 오른쪽"

/** 앱이 보낸 온도와 0.1도 이상 다르면 사람이 직접 바꾼 것 */
fun isUserTemp(sent: Float?, observed: Float): Boolean = sent != null && kotlin.math.abs(observed - sent) >= 0.1f

/**
 * 운전자 코칭은 정차 중에만 (명세서 안전 원칙: 주행 중 말 걸기 최소화).
 * 주행 중 급조작은 쌓아 두었다가 멈추면 한 번에 알려 준다.
 */
class CoachQueue {
    private var harsh = 0
    fun onHarsh() { harsh++ }
    fun clear() { harsh = 0 }
    /** 멈췄고(0.5m/s 미만) 쌓인 게 있으면 그 횟수를 돌려주고 비움, 아니면 0 */
    fun poll(speedMps: Double): Int {
        if (speedMps >= 0.5 || harsh == 0) return 0
        return harsh.also { harsh = 0 }
    }
}

/** 등받이 버튼을 눌렀을 때 할 일. Pleos는 P단에서만 시트를 움직이므로 주행 중에는 예약해 두고 정차(P)하면 젖힌다. */
enum class ReclineTap { NOW, RESERVE, CANCEL, NONE }

/** P단이거나 P단 여부를 모르면 바로 시도, 주행 중이면 예약(이미 예약했으면 취소) */
fun reclineTap(parked: Boolean?, reclined: Boolean, reserved: Boolean): ReclineTap = when {
    reclined -> ReclineTap.NONE
    parked != false -> ReclineTap.NOW
    reserved -> ReclineTap.CANCEL
    else -> ReclineTap.RESERVE
}

/** 예약해 둔 등받이는 P단에 들어서는 순간 한 번만 젖힌다 */
fun reclineOnPark(wasParked: Boolean?, nowParked: Boolean?, reserved: Boolean, reclined: Boolean): Boolean =
    reserved && !reclined && nowParked == true && wasParked != true
