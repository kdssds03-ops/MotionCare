# 멀미케어 공모전 대비 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 논문이 효과를 보인 방식과 앱의 실제 동작을 맞추고, 공모전 제출물을 요강에 맞게 정리한다. 1단계는 11/15 접수 마감 전, 2단계는 12/3 PT 전.

**Architecture:** 판단 로직은 지금처럼 화면·SDK와 분리된 순수 함수(`Comfort.kt`, `Profile.kt`, `Assist.kt`, 새 `Breath.kt`)에 두고 JUnit으로 테스트한다. `MainActivity.kt`는 이 함수들을 불러 SDK를 제어하는 연결만 바꾼다.

**Tech Stack:** Kotlin, Android (minSdk 28, compileSdk 35), Pleos Connect SDK (Vehicle 2.3.2, FusedLocation 1.1.3, TTS/STT 3.3.1, NaviHelper 3.0.5), JUnit 4

**Spec:** 공모전 요강 `../CLAUDE.md`(프로젝트 상위 폴더), 기능 명세 `../멀미케어_기능명세서.md`, 2026-11-04 대화의 논문 대조 검토(아래 Global Constraints에 핵심을 옮김)

## Global Constraints

- 모든 단위 테스트 명령: `./gradlew testDebugUnitTest` (MotionCare 폴더에서), 기존 테스트가 모두 통과해야 함
- 운전자에게는 음성만 사용하고, 운전자 코칭 음성은 정차 중(속도 < 0.5 m/s)에만 낸다 (명세서 안전 원칙)
- 공기 케어 온도: 현재 설정보다 1~2°C 낮게, 절대로 올리지 않음, 18~26°C 안 (명세서 F5)
- 호흡 박자: 4초 들숨 / 4초 날숨 (분당 7.5회) 유지 (Stromberg 2015)
- 기획서·README의 수치(테스트 개수 등)는 실제와 정확히 일치해야 함. 요강상 "사실과 다르면 선정 취소"
- APK: `apk/팀명_앱이름_release.apk`, release 서명, README 필수 항목(한 줄 소개, 실행 방법, 테스트 환경, APK 위치) 유지
- 기획서 02장과 첨부·링크에 실명·연락처·소속 노출 금지
- 1단계는 데모 코스(`demoDrive`)의 위험 단계 변화와 기존 점수 결과를 바꾸지 않는다 (제출 영상과 일치 유지)

## Review Focus

1. 멀미를 하지 않아 "도움 됨"을 고르지 않은 주행이 케어를 꺼버리는 경우 → 꺼지면 안 됨 (Task 1 `noSicknessTripsDoNotTurnCareOff`)
2. 호흡 가이드를 사용자가 직접 멈춘 뒤 위험이 계속 '높음'인 경우 → 저절로 다시 켜지면 안 됨 (Task 2 `stoppedBreathIsNotExtended`)
3. 겨울에 이미 낮은 온도(예: 19°C)로 맞춘 차 → 공기 케어가 온도를 올리면 안 됨 (Task 3 `neverWarmer`)
4. 내비 회전 정보가 정차 중(속도 0) 들어오는 경우 → 예고 시간 계산이 0으로 나누지 않고, 가까운 거리 기준으로만 판단 (Task 4 `stoppedUsesDistanceOnly`)
5. 급조작 직후 계속 달리는 경우 → 주행 중에는 말하지 않고, 정차하면 한 번만 말함 (Task 5 `speaksOnceWhenStopped`)

---

# 1단계 (11/15 접수 마감 전)

### Task 1: 멀미가 없었던 주행은 케어 자동 꺼짐 계산에서 제외

**Files:**
- Modify: `app/src/main/java/com/motioncare/Profile.kt:43-57` (`Profile.learn`)
- Test: `app/src/test/java/com/motioncare/ProfileTest.kt`

**Interfaces:**
- Produces: `fun countsForCareStats(t: Trip): Boolean` — `t.misc != null && t.misc >= 2`일 때만 true

- [ ] **Step 1: 테스트 작성**. 기존 `unhelpfulCareTurnsOff`는 `misc = null`로 3회 학습하므로 `misc = 5`로 바꾼다.

```kotlin
@Test fun noSicknessTripsDoNotTurnCareOff() {
    var p = base
    repeat(5) { p = p.learn(trip(0, cares = setOf(Care.TURN_CUE))) }
    assertFalse(p.cares[Care.TURN_CUE]?.off == true)
    assertEquals(0, p.cares[Care.TURN_CUE]?.runs ?: 0)
}

@Test fun unansweredTripsDoNotCount() {
    var p = base
    repeat(5) { p = p.learn(trip(null, cares = setOf(Care.AIR))) }
    assertEquals(0, p.cares[Care.AIR]?.runs ?: 0)
}
// unhelpfulCareTurnsOff: trip(null, ...) → trip(5, ...)
```

- [ ] **Step 2: 실패 확인.** `./gradlew testDebugUnitTest --tests com.motioncare.ProfileTest` → 새 테스트 2개 FAIL
- [ ] **Step 3: `countsForCareStats` 추가.** `learn`의 `for (c in trip.cares)` 루프를 `if (countsForCareStats(trip))` 안으로 옮긴다. 민감도 보정과 trips 기록은 지금처럼 모든 주행에 적용한다.
- [ ] **Step 4: 전체 통과 확인.** `./gradlew testDebugUnitTest` → 모두 PASS
- [ ] **Step 5: 리포트 화면 문구 확인.** `Screens.kt:374`의 "3번 넘게 써도 도움이 적으면 자동으로 꺼져요"를 "멀미했던 주행에서 3번 넘게 도움이 적으면 꺼져요"로 바꾼다.
- [ ] **Step 6: Commit** `fix: 멀미가 없던 주행은 케어 자동 꺼짐에서 제외`

### Task 2: 호흡 가이드를 위험이 이어지는 동안 계속하고, 소리로 박자 안내

**Files:**
- Create: `app/src/main/java/com/motioncare/Breath.kt`
- Modify: `app/src/main/java/com/motioncare/MainActivity.kt:481-512` (`breathing`, `stopBreathing`, `setAmbient` 예약), `:424-447` (tick)
- Modify: `app/src/main/java/com/motioncare/CareViews.kt:144-148` (BreathView 문구)
- Test: `app/src/test/java/com/motioncare/BreathTest.kt`

**Interfaces:**
- Produces (Breath.kt):
  - `const val BREATH_MIN_MS = 300_000L`, `BREATH_MAX_MS = 900_000L`, `BREATH_EXTEND_MS = 60_000L`, `BREATH_PHASE_MS = 4_000L`
  - `fun breathUntil(startMs: Long, untilMs: Long, nowMs: Long, risk: Risk): Long` — 진행 중(untilMs > nowMs)이고 risk ≥ CAUTION이면 `max(untilMs, min(nowMs + BREATH_EXTEND_MS, startMs + BREATH_MAX_MS))`, 아니면 untilMs 그대로
  - `fun isInhale(elapsedMs: Long): Boolean` — `(elapsedMs / BREATH_PHASE_MS) % 2 == 0L`

- [ ] **Step 1: 테스트 작성** (`BreathTest.kt`)

```kotlin
@Test fun extendsWhileRiskStays() =
    assertEquals(350_000L, breathUntil(0, 300_000, 290_000, Risk.CAUTION))
@Test fun neverBeyondMax() =
    assertEquals(900_000L, breathUntil(0, 890_000, 880_000, Risk.HIGH))
@Test fun lowRiskDoesNotExtend() =
    assertEquals(300_000L, breathUntil(0, 300_000, 290_000, Risk.LOW))
@Test fun stoppedBreathIsNotExtended() =   // 사용자가 멈추면 untilMs = 0
    assertEquals(0L, breathUntil(0, 0, 100_000, Risk.HIGH))
@Test fun phasesAlternateEvery4s() {
    assertTrue(isInhale(0)); assertTrue(isInhale(3_999))
    assertFalse(isInhale(4_000)); assertTrue(isInhale(8_000))
}
```

- [ ] **Step 2: 실패 확인.** `./gradlew testDebugUnitTest --tests com.motioncare.BreathTest` → 컴파일 실패
- [ ] **Step 3: `Breath.kt` 구현** (위 Interfaces 그대로)
- [ ] **Step 4: 통과 확인.** 같은 명령 → PASS
- [ ] **Step 5: MainActivity 연결**
  - `breathing()`: `breathStartMs = now`, `breathUntilMs = now + BREATH_MIN_MS`. 미리 15개를 예약하던 방식을 없애고, `BREATH_TOKEN`으로 4초마다 도는 Runnable 하나로 바꾼다. 매번 `isInhale(now - breathStartMs)`로 `setAmbient(inhale)`를 부르고, 짧은 톤을 낸다(`ToneGenerator(AudioManager.STREAM_MUSIC, 40)`, 들숨 `TONE_PROP_BEEP`, 날숨 `TONE_PROP_BEEP2`, 150ms). `breathActive()`가 false가 되면 `setAmbient(null)` 후 멈춘다.
  - 시작 음성은 "천천히 숨 쉬어 볼게요. 삐 소리가 나면 들이쉬고, 다음 소리에 내쉬세요. 눈은 앞쪽 창밖에 두세요."로 바꾼다.
  - tick: `risk = r` 바로 앞에 `breathUntilMs = breathUntil(breathStartMs, breathUntilMs, now, r)`를 넣는다.
  - `render()`의 `breathStart = breathUntilMs - 60_000`(`:995`)을 `breathStartMs`로 바꾼다.
  - `onDestroy`에서 ToneGenerator를 release한다.
- [ ] **Step 6: BreathView 문구.** 남은 시간 줄 아래에 "소리에 맞춰 · 눈은 창밖에"를 추가한다. 남은 시간은 `untilMs` 기준이므로 연장되면 같이 늘어난다.
- [ ] **Step 7: 에뮬레이터 확인.** 데모 "굽은 산길"에서 호흡이 1분 뒤에도 이어지는지, 4초마다 톤이 나는지, 누르면 멈추고 다시 켜지지 않는지 본다.
- [ ] **Step 8: Commit** `feat: 호흡 가이드를 위험이 이어지는 동안 유지하고 소리로 박자 안내`

### Task 3: 공기 케어 온도를 현재보다 1~2°C 낮게

**Files:**
- Modify: `app/src/main/java/com/motioncare/Profile.kt` (함수 추가)
- Modify: `app/src/main/java/com/motioncare/MainActivity.kt:385-395` (tempListener), `:470-477` (airCare 온도)
- Test: `app/src/test/java/com/motioncare/ProfileTest.kt`

**Interfaces:**
- Produces: `fun careTarget(currentC: Float?, prefC: Float): Float` — currentC가 null이면 prefC, 아니면 `prefC.coerceIn(currentC - 2f, currentC - 1f)`. 결과는 마지막에 `coerceIn(18f, 26f)`

- [ ] **Step 1: 테스트 작성**

```kotlin
@Test fun careTargetLowersOneToTwo() {
    assertEquals(22f, careTarget(24f, 21f), 1e-6f)     // 선호가 너무 낮으면 -2까지만
    assertEquals(23f, careTarget(24f, 23.5f), 1e-6f)   // 선호가 너무 높으면 -1
    assertEquals(21.5f, careTarget(23f, 21.5f), 1e-6f) // 범위 안이면 선호 그대로
}
@Test fun neverWarmer() = assertEquals(18f, careTarget(19f, 21f), 1e-6f)
@Test fun unknownCurrentUsesPreference() = assertEquals(21f, careTarget(null, 21f), 1e-6f)
```

- [ ] **Step 2: 실패 확인.** `./gradlew testDebugUnitTest --tests com.motioncare.ProfileTest` → 컴파일 실패
- [ ] **Step 3: `careTarget` 구현.** currentC가 있을 때 최종 값은 `minOf(prefC.coerceIn(currentC - 2f, currentC - 1f).coerceIn(18f, 26f), currentC)`이다. 바닥 18이 현재보다 높아지는 경우(현재 17°C 등)에도 현재 온도를 넘지 않는다.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: MainActivity 연결.** `observedTemp = HashMap<SeatArea, Float>()`를 두고, tempListener 첫 줄(`careTempArea` 비교보다 앞)에서 `observedTemp[a] = v`로 저장한다. airCare에서는 `careTarget(observedTemp[a], careTemp()).coerceIn(r)`를 쓴다. 음성은 "바람을 조금 세게, 온도는 살짝 낮출게요"로 바꾼다.
- [ ] **Step 6: Commit** `fix: 공기 케어가 현재 온도보다 1~2도 낮추도록`

### Task 4: 회전 예고를 회전 직전에, 짧게

**Files:**
- Modify: `app/src/main/java/com/motioncare/Assist.kt:42-66` (`shouldCueTurn`, `turnCueText`)
- Modify: `app/src/main/java/com/motioncare/MainActivity.kt:528-557` (`onSignal`, `onTbt`), tick에서 현재 속도 저장
- Test: `app/src/test/java/com/motioncare/AssistTest.kt` (`turnCueOncePerManeuver`, `turnCueTextRoundsDown` 수정)

**Interfaces:**
- Produces:
  - `fun shouldCueTurn(distanceM: Double, speedMps: Double, key: String, lastKey: String?): Boolean` — `key != lastKey`이고 `distanceM > 5`이며 다음 중 하나: `distanceM <= 40`, 또는 `speedMps >= 1 && distanceM / speedMps <= 4.0`
  - `fun turnCueText(side: Int): String` — `"곧 왼쪽"` / `"곧 오른쪽"`

- [ ] **Step 1: 테스트 수정·추가**

```kotlin
@Test fun cueAboutFourSecondsBefore() {
    assertFalse(shouldCueTurn(100.0, 14.0, "k", null))   // 7초 전
    assertTrue(shouldCueTurn(50.0, 14.0, "k", null))     // 3.6초 전
    assertFalse(shouldCueTurn(50.0, 14.0, "k", "k"))     // 같은 회전은 한 번
}
@Test fun stoppedUsesDistanceOnly() {
    assertFalse(shouldCueTurn(100.0, 0.0, "k", null))
    assertTrue(shouldCueTurn(30.0, 0.0, "k", null))
}
@Test fun turnCueTextIsShort() = assertEquals("곧 왼쪽", turnCueText(-1))
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: Assist.kt 구현.** 기존 "250m 뒤" 문구 함수는 삭제한다.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: MainActivity 연결.** tick에서 `curSpeed = t?.speed ?: liveSpeed`로 저장해 `onTbt`에 넘긴다. `onSignal`과 `onTbt` 모두 말하기 전에 같은 ToneGenerator로 `TONE_PROP_BEEP2`(120ms)를 내고, `say(turnCueText(side))`를 부른다. 같은 문구의 30초 반복 금지(SpeechGate)에 걸리지 않도록, 회전 예고는 `say` 대신 gate를 거치지 않는 `cue(text)`를 둔다(로그 기록은 동일하게).
- [ ] **Step 6: 에뮬레이터 확인.** 데모 "시내" 150초·180초 방향지시등에서 톤과 "곧 왼쪽"이 나고, 내비 시뮬레이션 안내에서 회전 직전에 한 번 나오는지 본다.
- [ ] **Step 7: Commit** `feat: 회전 예고를 회전 4초 전 짧은 소리와 한 마디로`

### Task 5: 운전자 코칭은 정차했을 때 한 번

**Files:**
- Modify: `app/src/main/java/com/motioncare/Assist.kt` (클래스 추가)
- Modify: `app/src/main/java/com/motioncare/MainActivity.kt:538-545` (`coach`), tick
- Test: `app/src/test/java/com/motioncare/AssistTest.kt`

**Interfaces:**
- Produces: `class CoachQueue { fun onHarsh(); fun poll(speedMps: Double): Int }` — 쌓인 급조작 횟수를 반환. 속도가 0.5 m/s 미만이고 쌓인 게 있을 때만 0보다 큰 값을 돌려주고 비운다. 2단계 Task 7이 `fun onRegen()`을 추가한다.

- [ ] **Step 1: 테스트 작성**

```kotlin
@Test fun speaksOnceWhenStopped() {
    val q = CoachQueue()
    q.onHarsh(); q.onHarsh()
    assertEquals(0, q.poll(10.0))
    assertEquals(2, q.poll(0.0))
    assertEquals(0, q.poll(0.0))
}
```

- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: 통과 확인**
- [ ] **Step 5: MainActivity 연결.** `comfort.harshJustNow`에서 `coachQ.onHarsh()`를 부른다. tick마다 `coachQ.poll(curSpeed)` 결과 n > 0이면 `coachCount += n` 후 "아까 흔들림이 있었어요. 출발과 제동을 조금 더 천천히 하면 동승자가 편해요."라고 말한다. 기존 30초 제한(`lastCoachMs`)은 삭제한다.
- [ ] **Step 6: Commit** `fix: 운전자 코칭을 정차 중에만`

### Task 6: 제출물을 요강에 맞게 정리 (11/14까지)

**Files:**
- Modify: `README.md` (테스트 개수, 기능 설명), `../제출자료/멀미케어_접수양식_기획서.docx`
- Rebuild: `apk/*.apk`, `../제출자료/*.apk`, 화면 캡처 png

- [ ] **Step 1: 테스트 개수 확정.** `./gradlew testDebugUnitTest` 후 `app/build/test-results/testDebugUnitTest/*.xml`의 `tests=` 합계를 센다. 이 숫자 N을 README와 기획서 5번 두 곳에 똑같이 적는다.
- [ ] **Step 2: README 갱신.** 주요 기능의 호흡(위험이 이어지는 동안, 소리 박자), 회전 예고(회전 직전 소리 + 한 마디), 코칭(정차 중)을 고친다.
- [ ] **Step 3: 기획서 문구 수정** (docx 스킬 사용, 서식 유지)
  - ② 호흡: "1분" 표현을 지우고 "위험이 이어지는 동안(5~15분), 삐 소리 박자"로 바꾼다
  - ② 회전 예고: "회전 약 4초 전 짧은 소리 + '곧 왼쪽'"으로 바꾼다. Reuten 인용은 "움직임을 미리 알려주는 신호가 실제 도로에서 멀미를 줄였다(진동 신호 기준 약 40%, 소리 신호는 효과가 더 작음)"처럼 조건을 밝힌다
  - ② 운전자 안내: "정차 중에 음성으로"
  - ② 공기 케어: "현재보다 1~2°C 낮게"
  - ④ 설문: "MSSQ-Short 축약" → "MSSQ를 참고한 간이 설문 3문항"
  - 시나리오 A-3): "호흡 가이드 1분" → "위험이 이어지는 동안 호흡 가이드"
  - 7번 참고자료: 파일명을 실제 제출 파일(`멀미케어_화면_주행중.png`, `멀미케어_화면_주행리포트.png`, `멀미케어_시연영상_1080p.mp4` 등)로 맞춘다
  - 5번 "단위 테스트 41개" → Step 1의 N
- [ ] **Step 4: release APK 재빌드.** `./gradlew assembleRelease` 후 `apk/<팀명>_멀미케어_release.apk`로 복사하고 README 링크와 설치 명령도 같은 이름으로 고친다. 팀명은 사용자에게 확인한다.
- [ ] **Step 5: 에뮬레이터 설치·실행 확인.** `adb install -g` → 데모 3코스 끝까지 진행 → 리포트 확인
- [ ] **Step 6: 화면 캡처 갱신.** 호흡 화면(문구 바뀜)과 주행 중 화면을 다시 찍어 `../제출자료/`에 넣는다. 이전 파일은 `이전버전/`으로 옮긴다.
- [ ] **Step 7: 사용자 작업 안내.** 시연 영상 재녹화(호흡·회전 예고가 바뀌었으므로), 기획서 01장 팀명·대표자 정보·서명, 숏폼(선택)은 사용자가 직접 해야 함을 알린다.
- [ ] **Step 8: Commit & push.** `docs: 제출물 수치와 문구를 실제 동작에 맞춤`. push는 사용자 확인 후에 한다.

---

# 2단계 (12/3 PT 전, 1차 합격 시)

### Task 7: 회생제동 추정과 정차 중 안내 (Xie 2025)

**Files:**
- Modify: `app/src/main/java/com/motioncare/Comfort.kt` (감지기 추가), `Assist.kt` (`CoachQueue.onRegen`), `MainActivity.kt` (Brake 리스너, 안내)
- Test: `ComfortTest.kt`, `AssistTest.kt`

**Interfaces:**
- Consumes: Task 5의 `CoachQueue`
- Produces:
  - `class RegenDetector { fun update(ax: Double, brakePressed: Boolean?, dt: Double): Boolean }` — `ax <= -1.5` m/s²가 브레이크 미사용(false) 상태로 1.0초 이상 이어지면 그 순간 한 번 true. brakePressed가 null(알 수 없음)이면 항상 false
  - `CoachQueue.onRegen()`. `poll`이 돌려주는 안내 종류를 구분하도록 `poll(speedMps): CoachNote?`로 바꾼다 (`data class CoachNote(val harsh: Int, val regen: Int)`)

- [ ] **Step 1: SDK 확인.** Pleos Playground Vehicle SDK 문서에서 브레이크 페달 상태 리스너와 회생제동 단계(DrivingMode) 조회 API의 정확한 이름을 찾아 이 Task에 적는다. 브레이크 상태가 없으면 Task 7은 "회생제동 단계 조회 + 정차 중 안내"만 한다.
- [ ] **Step 2: 테스트 작성.** `regenAfterOneSecondWithoutBrake`(dt 0.5, -2.0 m/s²를 4틱 → 두 번째 틱(누적 1.0초)에만 true, 첫·셋·넷째는 false), `brakeMeansNotRegen`(true면 false), `unknownBrakeNeverRegen`(null → false), CoachQueue regen이 정차 시 반환되는지
- [ ] **Step 3: 실패 확인 → 구현 → 통과 확인**
- [ ] **Step 4: MainActivity 연결.** 정차 시 regen ≥ 3이면 "회생제동 단계를 한 단계 낮추면 동승자가 덜 울렁거려요."라고 말한다(주행당 최대 2번). 데모 코스에서는 brakePressed를 false로 넣는다.
- [ ] **Step 5: Commit** `feat: 회생제동 추정과 정차 중 안내`

### Task 8: 오래 흔들린 만큼 위험 지수 반영

**Files:** Modify `Comfort.kt:41-89`, Test `ComfortTest.kt`

**Interfaces:**
- Produces: `Comfort.doseMsdv: Double` — 시간 상수 `DOSE_TAU_S = 1200.0`(20분)인 감쇠 누적 에너지의 제곱근. 위험 판정은 `max(windowMsdv, doseMsdv * DOSE_WEIGHT)`에 민감도를 곱해서 한다. `DOSE_WEIGHT`는 아래 테스트를 만족하는 값으로 정하고, 정한 값을 상수 주석에 남긴다
- snapshot/restore에 `doseEnergy` 추가

- [ ] **Step 1: 테스트 작성.** `longWindingRaisesMoreThanShort`(WINDING 20분 반복이 3분보다 최종 위험 ≥), `smoothStaysLow`(SMOOTH 코스 결과 maxRisk ≤ CAUTION 유지), 기존 `demoCoursesGiveDifferentScoresByDrivingStyle` 통과 유지
- [ ] **Step 2: 실패 확인 → 구현 → 전체 통과 확인**
- [ ] **Step 3: Commit** `feat: 위험 지수에 누적 노출 반영`

### Task 9: 좌석 보정과 MSSQ 기반 설문

**Files:** Modify `Profile.kt:26-31`, `Screens.kt:116`, `MainActivity.kt` (Comfort 생성 시 좌석 반영), Test `ProfileTest.kt`

**Interfaces:**
- Produces: `fun seatFactor(seat: Seat): Double` — 뒷좌석(REAR_LEFT, REAR_RIGHT) 1.15, 앞좌석 1.0 (Turner & Griffin 1999). Comfort 민감도 = `profile.sensitivity * seatFactor(seat)`. 학습되는 `profile.sensitivity`에는 곱하지 않는다
- `SURVEY` 3문항을 MSSQ-Short 문항에서 고른다: "어릴 때(12세 전) 차나 버스에서 멀미를 했나요?", "최근 10년 동안 차나 버스에서 멀미를 했나요?", "최근 10년 동안 배나 놀이기구에서 멀미를 했나요?". 답과 점수 구간은 그대로 둔다

- [ ] **Step 1: 테스트 작성**(`rearSeatWarnsEarlier`, 기존 `surveyMapsToSensitivity` 유지) → **Step 2: 실패 확인 → 구현 → 통과 확인**
- [ ] **Step 3: 기획서 ④ 설문 문구를 "MSSQ-Short 문항 3개 선택"으로 갱신**
- [ ] **Step 4: Commit** `feat: 뒷좌석 보정과 MSSQ 문항 설문`

### Task 10: 진동 예고 가능성 확인 (Reuten 2024)

- [ ] **Step 1:** Vehicle SDK Seat 도메인에 마사지·진동 제어가 있는지, 주행 중에도 허용되는지 문서와 에뮬레이터로 확인한다.
- [ ] **Step 2:** 가능하면 Task 4의 `cue` 직전에 해당 좌석 진동을 0.5초 켜는 별도 계획을 쓴다. 불가능하면 PT 자료에 "진동 시트 지원 차량에서 확장 예정"으로 적는다.

---

## 범위에서 뺀 것

- F10 좋아하는 음악: 미디어 SDK가 공식 SDK 목록에 없음. 기획서에서는 향후 계획으로만 둔다
- 편안함 점수의 구간별 공정성 보정: 실제 주행 데이터가 없어 지금 할 수 없음
- 감속 "예고": 앱이 감속을 미리 알 방법이 없음(내비 과속방지턱 정보 연동은 PT 이후)
