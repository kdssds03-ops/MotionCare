package com.motioncare

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class Risk { LOW, CAUTION, HIGH }

/**
 * 멀미 위험 지수와 동승자 편안함 점수.
 * ISO 2631-1 MSDV(멀미 유발 지수) 개념을 앞뒤·좌우 가속도에 적용한다.
 * sensitivity: 1.0 = 보통, 클수록 민감(더 일찍 경고).
 */
class Comfort(var sensitivity: Double = 1.0) {

    // ponytail: ISO Wf 정식 필터 대신 1차 고역(hpHz) + 1차 저역(0.5Hz) 근사.
    // 앞뒤는 0.1Hz부터. 좌우는 0.2Hz 아래의 느린 흔들림도 멀미를 일으켜서(Donohew & Griffin 2004)
    // 커브 하나에 10~30초 걸리는 굽은 길까지 잡도록 0.03Hz부터.
    // 실차 로그로 비교해 차이가 크면 ISO 2631-1 Wf 계수로 교체.
    private class Band(private val hpHz: Double) {
        private var started = false
        private var prevIn = 0.0
        private var hp = 0.0
        private var lp = 0.0
        fun apply(x: Double, dt: Double): Double {
            if (!started) { prevIn = x; started = true }
            val rcH = 1 / (2 * PI * hpHz)
            hp = rcH / (rcH + dt) * (hp + x - prevIn)
            prevIn = x
            val rcL = 1 / (2 * PI * 0.5)
            lp += dt / (rcL + dt) * (hp - lp)
            return lp
        }
    }

    private val bx = Band(LONG_HP_HZ)
    private val by = Band(LAT_HP_HZ)
    private var prevSpeed: Double? = null
    private var prevHeading: Double? = null
    private var windowEnergy = 0.0
    private var totalEnergy = 0.0
    private var harshActive = false

    var durationS = 0.0; private set
    var harshEvents = 0; private set
    var maxRisk = Risk.LOW; private set
    /** 이번 update에서 급가속/급감속/급회전이 새로 시작됐으면 true (운전자 코칭용) */
    var harshJustNow = false; private set
    /** 최근 약 3분간 MSDV (m/s^1.5) */
    var windowMsdv = 0.0; private set

    /**
     * speed m/s, heading 도(없으면 null), dt 초, steerDeg 핸들 각도(도, 없으면 null).
     * 좌우 흔들림은 핸들 각도가 있으면 그걸로(차량 센서라 빠르고 정확), 없으면 GPS 진행 방향 변화로 계산.
     */
    fun update(speed: Double, heading: Double?, dt: Double, steerDeg: Double? = null): Risk {
        val ax = prevSpeed?.let { (speed - it) / dt } ?: 0.0
        val yaw = when {
            // 자전거 모델: 요레이트 = 속도 × tan(바퀴 각도) / 축간거리
            steerDeg != null -> speed * tan(Math.toRadians(steerDeg / STEER_RATIO)) / WHEELBASE_M
            heading != null && prevHeading != null -> Math.toRadians(wrapDeg(heading - prevHeading!!)) / dt
            else -> 0.0
        }
        val ay = speed * yaw
        prevSpeed = speed
        if (heading != null) prevHeading = heading

        val ax2 = bx.apply(ax, dt)
        val ay2 = by.apply(ay, dt)
        val aw2 = ax2 * ax2 + ay2 * ay2
        windowEnergy = windowEnergy * exp(-dt / WINDOW_S) + aw2 * dt
        totalEnergy += aw2 * dt
        durationS += dt
        windowMsdv = sqrt(windowEnergy)

        val harsh = abs(ax) > HARSH_LONG || abs(ay) > HARSH_LAT
        harshJustNow = harsh && !harshActive
        if (harshJustNow) harshEvents++
        harshActive = harsh

        val r = when {
            windowMsdv * sensitivity >= HIGH_MSDV -> Risk.HIGH
            windowMsdv * sensitivity >= CAUTION_MSDV -> Risk.CAUTION
            else -> Risk.LOW
        }
        if (r > maxRisk) maxRisk = r
        return r
    }

    /** 앱이 다시 켜져도 주행을 이어가기 위한 상태 (저장용) */
    fun snapshot(): Map<String, Double> = mapOf(
        "windowEnergy" to windowEnergy, "totalEnergy" to totalEnergy, "durationS" to durationS,
        "harshEvents" to harshEvents.toDouble(), "maxRisk" to maxRisk.ordinal.toDouble(), "sensitivity" to sensitivity,
    )

    // ponytail: 필터 내부 상태와 직전 속도는 복원하지 않는다. 몇 초 안에 다시 안정되므로 충분. 문제가 보이면 함께 저장.
    fun restore(m: Map<String, Double>) {
        windowEnergy = m["windowEnergy"] ?: 0.0
        totalEnergy = m["totalEnergy"] ?: 0.0
        durationS = m["durationS"] ?: 0.0
        harshEvents = (m["harshEvents"] ?: 0.0).toInt()
        maxRisk = Risk.entries[(m["maxRisk"] ?: 0.0).toInt().coerceIn(0, Risk.entries.size - 1)]
        sensitivity = m["sensitivity"] ?: sensitivity
        windowMsdv = sqrt(windowEnergy)
    }

    /** 0~100점. 주로 시간 보정한 누적 MSDV(멀미를 일으키는 흔들림 양)로, 급조작 횟수는 조금만 감점. */
    fun score(): Int {
        val minutes = durationS / 60
        if (minutes < 1) return 100
        // MSDV는 √시간에 비례해 커지므로 √분으로 나눠 주행 길이 영향 제거
        val rate = sqrt(totalEnergy) / sqrt(minutes)
        // ponytail: 같은 구간 평균 대비 보정은 아직 없음. 주행 데이터가 쌓이면 구간별 기준선 도입.
        return (100 - SCORE_PER_EVENT * harshEvents - SCORE_PER_RATE * rate).toInt().coerceIn(0, 100)
    }

    /** 하차 후 MISC(0~10) 피드백으로 민감도 자동 조정 */
    fun feedback(misc: Int) {
        sensitivity = adjustSensitivity(sensitivity, misc, maxRisk)
    }

    companion object {
        // 캘리브레이션 값. 실차 테스트하며 조정.
        const val WINDOW_S = 180.0
        const val CAUTION_MSDV = 6.0
        const val HIGH_MSDV = 10.0
        const val HARSH_LONG = 2.5   // m/s²
        const val HARSH_LAT = 3.0    // m/s²
        // 시뮬레이터 검증(2026-11-04): 급조작 비중이 크면 오래 흔들린 굽은 길이 높은 점수를 받아서 흔들림 쪽으로 옮김
        const val SCORE_PER_EVENT = 0.5
        const val SCORE_PER_RATE = 5.0
        const val LONG_HP_HZ = 0.1   // 앞뒤 흔들림 필터 하한
        const val LAT_HP_HZ = 0.03   // 좌우 흔들림 필터 하한
        // 차량 값: SDK에 축간거리 API가 없어 아이오닉 5 기준 상수. 차종별로 보정.
        const val WHEELBASE_M = 3.0
        const val STEER_RATIO = 14.0     // 핸들 각도 ÷ 바퀴 각도

        fun wrapDeg(d: Double): Double = ((d + 540) % 360) - 180
    }
}

/** 시연용 가상 주행 한 틱 */
data class DemoTick(val speed: Double, val heading: Double, val signal: Int /* -1 좌, 0, 1 우 */)

/** 시연용 가상 주행 코스. 모두 6분 20초, 같은 점수 계산식으로 운전 방식만 다름. */
enum class DemoCourse(val title: String, val sub: String) {
    SMOOTH("부드러운 운전", "천천히 가속 · 완만한 길"),
    WINDING("굽은 산길", "계속 이어지는 굽은 길"),
    CITY("시내 가다 서다", "급한 가감속 · 굽은 길 · 고속도로"),
}

/**
 * 시연용 6분 20초 주행 (0.5초 간격).
 * CITY: 0~60초 부드러운 주행 → 60~150초 가다 서다 → 150~210초 굽은 길 → 210~360초 고속도로(회복 구간) → 360~380초 정차.
 * SMOOTH: 천천히 가속 → 완만한 길과 넓은 커브 → 고속도로 → 천천히 정차.
 * WINDING: 비슷한 속도로 계속 이어지는 굽은 길 → 정차.
 * rnd를 주면 실제 주행처럼 속도·커브·가다 서다 박자가 매번 조금씩 달라짐 (코스 성격은 유지). null이면 항상 같음(테스트용).
 */
fun demoDrive(dt: Double = 0.5, course: DemoCourse = DemoCourse.CITY, rnd: kotlin.random.Random? = null): List<DemoTick> {
    // ±pct 범위의 배율 하나 (rnd가 없으면 1)
    fun k(pct: Double) = if (rnd == null) 1.0 else 1 + (rnd.nextDouble() * 2 - 1) * pct
    return when (course) {
        // 굽은 길의 좌우 흔들림이 급한 움직임 기준(3.0m/s²) 근처면 조금만 달라져도 횟수가 20회씩 뛰어서, 기준보다 확실히 낮게(약 2.6)
        DemoCourse.CITY -> cityDrive(dt, speedK = k(0.03), stopGo = 4.0 * k(0.06), period = 8.0 * k(0.03), curve = 0.23 * k(0.03))
        DemoCourse.SMOOTH -> smoothDrive(dt, speedK = k(0.08), wob = 1.0 * k(0.6), curve = 0.06 * k(0.3),
            // 부드럽게 가다가도 가끔 살짝 세게 밟는 브레이크 0~3번 (시각은 60~190초 사이)
            brakes = List(rnd?.nextInt(0, 4) ?: 0) { 60.0 + rnd!!.nextDouble() * 130 })
        DemoCourse.WINDING -> windingDrive(dt, base = 12.0 * k(0.07), amp = 0.21 * k(0.07), wob = 1.5 * k(0.5))
    }
}

private fun smoothDrive(dt: Double, speedK: Double, wob: Double, curve: Double, brakes: List<Double>): List<DemoTick> {
    val out = ArrayList<DemoTick>()
    var heading = 0.0
    var t = 0.0
    while (t < 380) {
        // 브레이크: 1.5초 동안 4m/s 줄였다가 1.5초 동안 회복 (약 2.7m/s², 급한 움직임 기준 2.5 바로 위)
        val dip = brakes.sumOf { b -> val x = t - b; if (x in 0.0..3.0) 4.0 * (1 - abs(x - 1.5) / 1.5) else 0.0 }
        val speed = speedK * when {
            t < 40 -> 14.0 * t / 40
            t < 200 -> 14.0 + wob * sin(2 * PI * (t - 40) / 40) - dip
            t < 240 -> 14.0 + 8.0 * (t - 200) / 40
            t < 340 -> 22.0
            else -> 22.0 * (380 - t) / 40
        }
        var signal = 0
        if (t in 100.0..180.0) {   // 넓은 커브 두 번
            heading += Math.toDegrees(curve * 2 * PI / 40 * kotlin.math.cos(2 * PI * (t - 100) / 40)) * dt
            if ((t - 100) % 40 < 1.5) signal = if (t < 140) -1 else 1
        }
        out += DemoTick(speed, (heading + 360) % 360, signal)
        t += dt
    }
    return out
}

private fun windingDrive(dt: Double, base: Double, amp: Double, wob: Double): List<DemoTick> {
    val out = ArrayList<DemoTick>()
    var heading = 0.0
    var t = 0.0
    while (t < 380) {
        val speed = when {
            t < 25 -> base * t / 25
            t < 350 -> base + wob * sin(2 * PI * t / 20)
            else -> (base + wob * sin(2 * PI * 350 / 20)) * (380 - t) / 30
        }
        var signal = 0
        // 산을 돌아가는 큰 굽이: 4분 주기의 아주 느린 방향 변화라 멀미 필터(0.03Hz 이상)에는 거의 안 걸리고
        // 좌우 가속도도 약 0.2m/s²만 더함. 리포트에 그린 길이 실제 산길처럼 굽어 보이게.
        if (t in 25.0..350.0) heading += Math.toDegrees(BEND_YAW * sin(2 * PI * (t - 25) / BEND_PERIOD_S)) * dt
        if (t in 40.0..340.0) {   // 10초마다 좌우로 이어지는 굽은 길
            heading += Math.toDegrees(amp * 2 * PI / 10 * kotlin.math.cos(2 * PI * (t - 40) / 10)) * dt
            if ((t - 40) % 40 < 1.5) signal = if (((t - 40) / 40).toInt() % 2 == 0) -1 else 1
        }
        out += DemoTick(speed, (heading + 360) % 360, signal)
        t += dt
    }
    return out
}

private fun cityDrive(dt: Double, speedK: Double, stopGo: Double, period: Double, curve: Double): List<DemoTick> {
    val out = ArrayList<DemoTick>()
    var heading = 0.0
    var t = 0.0
    while (t < 380) {
        val speed = speedK * when {
            t < 20 -> 14.0 * t / 20
            t < 60 -> 14.0
            t < 150 -> 9.0 + stopGo * sin(2 * PI * (t - 60) / period)
            t < 210 -> 11.0
            t < 230 -> 11.0 + 11.0 * (t - 210) / 20     // 고속도로 합류: 천천히 가속
            t < 360 -> 22.0
            else -> 22.0 * (380 - t) / 20
        }
        var signal = 0
        if (t in 150.0..210.0) {
            val ph = 2 * PI * (t - 150) / 6
            heading += Math.toDegrees(curve * 2 * PI / 6 * kotlin.math.cos(ph)) * dt
            // 굽은 길 진입(150초)과 중간(180초)에 한 번씩만 방향지시등
            if (t < 209 && (t - 150) % 30 < 1.5) signal = if (t < 180) -1 else 1
        }
        out += DemoTick(speed, (heading + 360) % 360, signal)
        t += dt
    }
    return out
}

/** 주행 화면 "나아지고 있어요": 주의 단계에서 20초 전보다 10% 넘게 내려갔을 때만 (가다 서다의 잠깐 떨어짐은 제외) */
fun isEasing(nowIdx: Double, idx20sAgo: Double, risk: Risk): Boolean =
    risk == Risk.CAUTION && nowIdx < idx20sAgo * 0.9

private const val BEND_YAW = 0.015          // rad/s, 굽은 산길의 큰 굽이 최대 회전 속도
private const val BEND_PERIOD_S = 240.0
