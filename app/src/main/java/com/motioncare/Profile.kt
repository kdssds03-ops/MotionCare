package com.motioncare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

/**
 * 케어 효과를 셀 수 있는 주행: 멀미가 있었다고 답한 주행(MISC 2 이상)만.
 * 멀미가 없었거나(예방이 됐을 수 있음) 답하지 않은 주행은 "도움 됨"을 고를 이유가 없어서,
 * 세면 효과 있던 케어까지 꺼진다.
 */
fun countsForCareStats(t: Trip): Boolean = t.misc != null && t.misc >= 2

fun Profile.learn(trip: Trip): Profile {
    val stats = cares.toMutableMap()
    if (countsForCareStats(trip)) for (c in trip.cares) {
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

/** 1분도 안 된 주행(잘못 누름, 이어하기 직후 종료)은 점수가 의미 없어 기록하지 않는다 */
fun worthKeeping(t: Trip) = t.minutes >= 1

/**
 * 기록용 주행 시간(분): 버림. 반올림하면 30~59초 주행이 1분이 되어 기록되는데,
 * 점수는 1분 미만이면 판단하지 않고 100점을 주므로 "100점" 기록이 잘못 남았음.
 */
fun tripMinutes(durationS: Double) = (durationS / 60).toInt()

/** 사용자가 직접 끔. 기록은 그대로 둔다. */
fun Profile.disableCare(c: Care): Profile = copy(cares = cares + (c to (cares[c] ?: CareStat()).copy(off = true)))

fun careName(c: Care) = when (c) { Care.AIR -> "공기 케어"; Care.BREATH -> "호흡 가이드"; Care.TURN_CUE -> "회전 예고"; Care.RECLINE -> "등받이" }

/** 리포트의 학습 결과 한 줄: 경고 시점 변화 + 새로 꺼진 케어 */
fun learningLine(before: Profile, after: Profile): String {
    val d = after.earlierPercent() - before.earlierPercent()
    val timing = when {
        d > 0 -> "다음부터 경고가 $d% 일찍 와요"
        d < 0 -> "다음부터 경고가 ${-d}% 늦게 와요"
        else -> "경고 시점은 그대로예요"
    }
    val off = Care.entries.filter { after.cares[it]?.off == true && before.cares[it]?.off != true }
    return if (off.isEmpty()) timing else "$timing · 효과가 적었던 ${off.joinToString(", ") { careName(it) }}는 꺼 둘게요"
}

fun learnTemp(observedC: Float): Float = observedC.coerceIn(18f, 26f)

/** 기본 대비 몇 % 일찍 경고하는지 (음수면 늦게) */
fun Profile.earlierPercent(): Int = ((sensitivity - 1.0) * 100).roundToInt()

/** 진행 중인 주행. profileId가 null이면 게스트이고 파일에 남기지 않는다. */
data class Ongoing(
    val profileId: String?, val demo: Boolean, val startMs: Long,
    val comfort: Map<String, Double>, val cares: Set<Care>,
)

/** 차 안(앱 내부 저장소)에만 저장. 임시 파일에 쓰고 교체해서, 쓰는 중에 꺼져도 깨지지 않게 한다. */
class ProfileStore(private val dir: File) {
    private val profiles = File(dir, "profiles.json")
    private val ongoing = File(dir, "ongoing.json")

    fun load(): List<Profile> = runCatching {
        val a = JSONArray(profiles.readText())
        List(a.length()) { a.getJSONObject(it).toProfile() }
    }.getOrElse {
        // 읽을 수 없는 파일은 지우지 않고 .bad로 옮겨 둠 (다음 저장이 덮어써서 모두 잃지 않게)
        if (profiles.exists()) profiles.renameTo(File(dir, "profiles.json.bad"))
        emptyList()
    }

    fun save(list: List<Profile>) = write(profiles, JSONArray(list.map { it.toJson() }).toString())

    fun saveOngoing(o: Ongoing?) {
        if (o?.profileId == null) { ongoing.delete(); return }
        write(ongoing, JSONObject()
            .put("profileId", o.profileId).put("demo", o.demo).put("startMs", o.startMs)
            .put("comfort", JSONObject(o.comfort)).put("cares", JSONArray(o.cares.map { it.name }))
            .toString())
    }

    fun loadOngoing(): Ongoing? = runCatching {
        val j = JSONObject(ongoing.readText())
        val c = j.getJSONObject("comfort")
        Ongoing(
            j.getString("profileId"), j.getBoolean("demo"), j.getLong("startMs"),
            c.keys().asSequence().associateWith { c.getDouble(it) }, cares(j.getJSONArray("cares")),
        )
    }.getOrNull()

    private fun write(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

private fun cares(a: JSONArray): Set<Care> = List(a.length()) { Care.valueOf(a.getString(it)) }.toSet()

private fun Profile.toJson() = JSONObject()
    .put("id", id).put("name", name).put("seat", seat.name).put("sensitivity", sensitivity)
    .put("careTempC", careTempC.toDouble())
    .put("cares", JSONObject(cares.mapKeys { it.key.name }.mapValues {
        JSONObject().put("runs", it.value.runs).put("helped", it.value.helped).put("off", it.value.off)
    }))
    .put("trips", JSONArray(trips.map { t ->
        JSONObject().put("startMs", t.startMs).put("minutes", t.minutes).put("score", t.score)
            .put("maxRisk", t.maxRisk.name).put("misc", t.misc ?: JSONObject.NULL)
            .put("cares", JSONArray(t.cares.map { it.name })).put("helped", JSONArray(t.helped.map { it.name }))
            .put("demo", t.demo)
    }))

private fun JSONObject.toProfile(): Profile {
    val c = getJSONObject("cares")
    val t = getJSONArray("trips")
    return Profile(
        id = getString("id"), name = getString("name"), seat = Seat.valueOf(getString("seat")),
        sensitivity = getDouble("sensitivity"), careTempC = getDouble("careTempC").toFloat(),
        cares = c.keys().asSequence().associate { k ->
            val s = c.getJSONObject(k)
            Care.valueOf(k) to CareStat(s.getInt("runs"), s.getInt("helped"), s.getBoolean("off"))
        },
        trips = List(t.length()) {
            val j = t.getJSONObject(it)
            Trip(
                j.getLong("startMs"), j.getInt("minutes"), j.getInt("score"), Risk.valueOf(j.getString("maxRisk")),
                if (j.isNull("misc")) null else j.getInt("misc"),
                cares(j.getJSONArray("cares")), cares(j.getJSONArray("helped")), j.getBoolean("demo"),
            )
        },
    )
}
