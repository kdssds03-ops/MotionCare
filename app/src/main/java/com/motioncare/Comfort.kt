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

    // ponytail: ISO Wf 정식 필터 대신 1차 고역(0.1Hz) + 1차 저역(0.5Hz) 근사.
    // 실차 로그로 비교해 차이가 크면 ISO 2631-1 Wf 계수로 교체.
    private class Band {
        private var started = false
        private var prevIn = 0.0
        private var hp = 0.0
        private var lp = 0.0
        fun apply(x: Double, dt: Double): Double {
            if (!started) { prevIn = x; started = true }
            val rcH = 1 / (2 * PI * 0.1)
            hp = rcH / (rcH + dt) * (hp + x - prevIn)
            prevIn = x
            val rcL = 1 / (2 * PI * 0.5)
            lp += dt / (rcL + dt) * (hp - lp)
            return lp
        }
    }

    private val bx = Band()
    private val by = Band()
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

    /** 0~100점. 급조작 횟수 + 시간 보정한 누적 MSDV로 감점. */
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
        if (misc >= 4 && maxRisk != Risk.HIGH) sensitivity *= 1.1   // 놓쳤다 → 더 민감하게
        if (misc <= 1 && maxRisk == Risk.HIGH) sensitivity *= 0.9   // 과잉 경고 → 덜 민감하게
        sensitivity = sensitivity.coerceIn(0.5, 2.0)
    }

    companion object {
        // 캘리브레이션 값. 실차 테스트하며 조정.
        const val WINDOW_S = 180.0
        const val CAUTION_MSDV = 6.0
        const val HIGH_MSDV = 10.0
        const val HARSH_LONG = 2.5   // m/s²
        const val HARSH_LAT = 3.0    // m/s²
        const val SCORE_PER_EVENT = 1.5
        const val SCORE_PER_RATE = 3.0
        // 차량 값: SDK에 축간거리 API가 없어 아이오닉 5 기준 상수. 차종별로 보정.
        const val WHEELBASE_M = 3.0
        const val STEER_RATIO = 14.0     // 핸들 각도 ÷ 바퀴 각도

        fun wrapDeg(d: Double): Double = ((d + 540) % 360) - 180
    }
}

/** 시연용 가상 주행 한 틱 */
data class DemoTick(val speed: Double, val heading: Double, val signal: Int /* -1 좌, 0, 1 우 */)

/**
 * 시연용 6분 20초 주행 (0.5초 간격).
 * 0~60초 부드러운 주행 → 60~150초 가다 서다 → 150~210초 굽은 길
 * → 210~360초 고속도로(회복 구간) → 360~380초 정차.
 */
fun demoDrive(dt: Double = 0.5): List<DemoTick> {
    val out = ArrayList<DemoTick>()
    var heading = 0.0
    var t = 0.0
    while (t < 380) {
        val speed = when {
            t < 20 -> 14.0 * t / 20
            t < 60 -> 14.0
            t < 150 -> 9.0 + 4.0 * sin(2 * PI * (t - 60) / 8)
            t < 210 -> 11.0
            t < 230 -> 11.0 + 11.0 * (t - 210) / 20     // 고속도로 합류: 천천히 가속
            t < 360 -> 22.0
            else -> 22.0 * (380 - t) / 20
        }
        var signal = 0
        if (t in 150.0..210.0) {
            val ph = 2 * PI * (t - 150) / 6
            heading += Math.toDegrees(0.26 * 2 * PI / 6 * kotlin.math.cos(ph)) * dt
            // 굽은 길 진입(150초)과 중간(180초)에 한 번씩만 방향지시등
            if (t < 209 && (t - 150) % 30 < 1.5) signal = if (t < 180) -1 else 1
        }
        out += DemoTick(speed, (heading + 360) % 360, signal)
        t += dt
    }
    return out
}
