package com.motioncare

import kotlin.math.roundToInt

enum class Seat { FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }
enum class Care { AIR, BREATH, TURN_CUE, RECLINE }

data class CareStat(val runs: Int = 0, val helped: Int = 0, val off: Boolean = false)

data class Trip(
    val startMs: Long, val minutes: Int, val score: Int, val maxRisk: Risk,
    val misc: Int?, val cares: Set<Care>, val helped: Set<Care>, val demo: Boolean,
)

/** 자주 타는 동승자. 게스트는 Profile 없이(null) 주행한다. */
data class Profile(
    val id: String, val name: String, val seat: Seat, val sensitivity: Double,
    val careTempC: Float = 21f,
    val cares: Map<Care, CareStat> = emptyMap(),
    val trips: List<Trip> = emptyList(),   // 최신이 맨 앞
)

/** 설문 3문항(각 0 아니요 · 1 가끔 · 2 자주) 합계 → 초기 민감도 */
fun surveySensitivity(answers: List<Int>): Double = when (answers.sum()) {
    in 0..1 -> 0.8
    in 2..4 -> 1.0
    else -> 1.3
}

/** 멀미했는데 위험 단계에 못 갔으면 더 일찍, 괜찮았는데 위험까지 갔으면 더 늦게 경고 */
fun adjustSensitivity(s: Double, misc: Int, maxRisk: Risk): Double {
    var n = s
    if (misc >= 4 && maxRisk != Risk.HIGH) n *= 1.1
    if (misc <= 1 && maxRisk == Risk.HIGH) n *= 0.9
    return n.coerceIn(0.5, 2.0)
}

const val MAX_TRIPS = 50

fun Profile.learn(trip: Trip): Profile {
    val stats = cares.toMutableMap()
    for (c in trip.cares) {
        val old = stats[c] ?: CareStat()
        val runs = old.runs + 1
        val helped = old.helped + if (c in trip.helped) 1 else 0
        // 3번 넘게 써도 도움이 1/3 미만이면 꺼 둔다 (사용자가 다시 켤 수 있음)
        stats[c] = CareStat(runs, helped, off = old.off || (runs >= 3 && helped * 3 < runs))
    }
    return copy(
        sensitivity = trip.misc?.let { adjustSensitivity(sensitivity, it, trip.maxRisk) } ?: sensitivity,
        cares = stats,
        trips = (listOf(trip) + trips).take(MAX_TRIPS),
    )
}

/** 다시 켜면 기록을 처음부터 쌓는다. 그래야 바로 다시 꺼지지 않는다. */
fun Profile.enableCare(c: Care): Profile = copy(cares = cares + (c to CareStat()))

fun learnTemp(observedC: Float): Float = observedC.coerceIn(18f, 26f)

/** 기본 대비 몇 % 일찍 경고하는지 (음수면 늦게) */
fun Profile.earlierPercent(): Int = ((sensitivity - 1.0) * 100).roundToInt()
