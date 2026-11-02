# 멀미케어 v2 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프로필 개인화(게스트 포함), "속이 안 좋아요" 즉시 케어와 휴게소 경유, 내비 회전 예고, 주행 리포트를 넣고, Pleos 차량 앱 수준의 화면과 안정성으로 끌어올린다.

**Architecture:** 판단·계산·저장은 Android에 의존하지 않는 순수 Kotlin 파일(`Comfort.kt`, `Profile.kt`, `Assist.kt`)에 두고 JUnit으로 테스트한다. `MainActivity.kt`는 차량 SDK 연결과 주행 흐름만 맡고, 화면 그리기는 `Screens.kt`, 디자인 토큰과 공통 부품은 `Ui.kt`가 맡는다. 화면 전환은 한 Activity 안에서 루트 뷰를 바꾸는 방식이다.

**Tech Stack:** Kotlin 2.0, Android(minSdk 28, compileSdk 35), 코드로 만든 View, Pleos SDK(Vehicle 2.3.2, FusedLocation 1.1.3, TextToSpeech 3.3.1, SpeechToText 3.3.1, NaviHelper 3.0.5), `org.json`(Android 내장), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-02-motioncare-v2-design.md`

## Global Constraints
- 새 런타임 라이브러리 추가 금지. 예외: Pleos SDK `SpeechToText:3.3.1`, `NaviHelper:3.0.5`(설계에 포함), 테스트 전용 `org.json:json:20240303`(JVM 테스트에서 Android `org.json`이 비어 있어서).
- 텍스트 최소 18sp, 핵심 수치 46sp 이하(48 미만), 대비 4.5:1 이상, 한 문장 3줄 이내.
- 터치 대상 높이 64dp 이상, 간격 16dp 이상. 누르면 즉시 눌림 표시.
- 주간/야간: 시스템 `uiMode` 야간 여부로 팔레트를 고른다.
- 세로(창 폭 1000dp 미만)는 한 열, 가로는 2열.
- 프로필 생성·편집(키보드)은 `CarUxRestrictions`에 `UX_RESTRICTIONS_NO_KEYBOARD`가 없을 때만. 시트 이동은 기어 P일 때만.
- 게스트 데이터는 파일에 쓰지 않는다.
- 저장은 임시 파일에 쓴 뒤 교체(원자적 저장).
- 같은 음성 안내는 30초 안에 반복하지 않는다.
- STT는 `Mode.ON_DEVICE`, 권한 `pleos.car.permission.STT_SERVICE`. NaviHelper 권한 `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`.
- 의도적 단순화에는 `// ponytail:` 주석으로 한계와 개선 방법을 남긴다.

## Review Focus
1. 프로필 파일이 깨졌거나 없음 → 앱이 빈 목록으로 시작하고 꺼지지 않는다. (Task 2 테스트)
2. 게스트로 주행을 끝내거나 앱을 다시 켜도 게스트 기록이 파일에 남지 않는다. (Task 2 테스트)
3. 주행 중 "새 프로필"을 누름 → 키보드가 뜨지 않고 이유를 보여준다. (Task 6 에뮬레이터 확인)
4. 음성 인식 실패·마이크 없음·알아듣지 못함 → 버튼 흐름은 그대로 동작하고 한 줄 안내. (Task 3 테스트 + Task 7 확인)
5. 50km 안에 휴게소 없음, 내비 연결 실패 → 안내만 하고 케어는 계속. (Task 3 테스트 + Task 7 확인)

---

### Task 1: 프로필 모델과 학습 규칙

**Files:**
- Create: `app/src/main/java/com/motioncare/Profile.kt`
- Modify: `app/src/main/java/com/motioncare/Comfort.kt` (`feedback` → `adjustSensitivity` 사용)
- Test: `app/src/test/java/com/motioncare/ProfileTest.kt`

**Interfaces:**
- Produces:
  - `enum class Seat { FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }`, `enum class Care { AIR, BREATH, TURN_CUE, RECLINE }`
  - `data class CareStat(val runs: Int = 0, val helped: Int = 0, val off: Boolean = false)`
  - `data class Trip(val startMs: Long, val minutes: Int, val score: Int, val maxRisk: Risk, val misc: Int?, val cares: Set<Care>, val helped: Set<Care>, val demo: Boolean)`
  - `data class Profile(val id: String, val name: String, val seat: Seat, val sensitivity: Double, val careTempC: Float = 21f, val cares: Map<Care, CareStat> = emptyMap(), val trips: List<Trip> = emptyList())`
  - `fun surveySensitivity(answers: List<Int>): Double`
  - `fun adjustSensitivity(s: Double, misc: Int, maxRisk: Risk): Double`
  - `fun Profile.learn(trip: Trip): Profile`
  - `fun Profile.enableCare(c: Care): Profile`
  - `fun learnTemp(observedC: Float): Float`
  - `fun Profile.earlierPercent(): Int`

- [ ] **Step 1: 실패하는 테스트 작성** — `ProfileTest`:
  - `surveyMapsToSensitivity`: `[0,0,1]`→0.8, `[1,1,0]`→1.0, `[2,1,2]`→1.3 (합 0~1→0.8, 2~4→1.0, 5~6→1.3)
  - `sickWithoutHighMakesEarlier`: `adjustSensitivity(1.0, 6, Risk.CAUTION)` = 1.1
  - `calmButHighMakesLater`: `adjustSensitivity(1.0, 0, Risk.HIGH)` = 0.9
  - `sensitivityClamped`: `adjustSensitivity(1.95, 9, Risk.LOW)` = 2.0, `adjustSensitivity(0.52, 0, Risk.HIGH)` = 0.5
  - `learnAddsTripAndKeeps50`: 50개 기록이 있는 프로필에 `learn` → 50개 유지, 새 기록이 맨 앞
  - `unhelpfulCareTurnsOff`: AIR가 3번 실행·도움 0 → `cares[AIR].off == true`; 3번 중 1번 도움 → off 아님
  - `reEnableResetsStats`: `enableCare(AIR)` → `CareStat()`(runs 0, helped 0, off false)
  - `miscNullDoesNotChangeSensitivity`: 피드백 없는 기록은 민감도 그대로
  - `tempClamped`: `learnTemp(16f)`=18f, `learnTemp(30f)`=26f, `learnTemp(22.5f)`=22.5f
  - `earlierPercent`: 민감도 1.2 → 20, 0.9 → -10
- [ ] **Step 2: 실행해서 실패 확인** — `./gradlew testDebugUnitTest --tests '*ProfileTest*'` → 컴파일 실패
- [ ] **Step 3: 구현** — 위 Interfaces 그대로. `Comfort.feedback`은 `sensitivity = adjustSensitivity(sensitivity, misc, maxRisk)` 한 줄로 바꾼다. 기록은 최근 50개.
- [ ] **Step 4: 전체 테스트 통과 확인** — `./gradlew testDebugUnitTest` → 모두 PASS (기존 6개 포함)
- [ ] **Step 5: 커밋** — `feat: 프로필 모델과 학습 규칙`

### Task 2: 저장과 진행 중 주행 복원

**Files:**
- Modify: `app/src/main/java/com/motioncare/Profile.kt` (JSON 변환, `ProfileStore`)
- Modify: `app/src/main/java/com/motioncare/Comfort.kt` (`snapshot`/`restore`)
- Modify: `app/build.gradle.kts` (`testImplementation("org.json:json:20240303")`)
- Test: `ProfileTest.kt`, `ComfortTest.kt`

**Interfaces:**
- Consumes: Task 1 모델
- Produces:
  - `class ProfileStore(private val dir: File) { fun load(): List<Profile>; fun save(list: List<Profile>); fun saveOngoing(o: Ongoing?); fun loadOngoing(): Ongoing? }` (파일 `profiles.json`, `ongoing.json`)
  - `data class Ongoing(val profileId: String?, val demo: Boolean, val startMs: Long, val comfort: Map<String, Double>, val cares: Set<Care>)` — `profileId == null`이면 게스트이고 **저장하지 않는다**(`saveOngoing`이 게스트면 파일을 지운다)
  - `fun Comfort.snapshot(): Map<String, Double>`, `fun Comfort.restore(m: Map<String, Double>)` (`windowEnergy, totalEnergy, durationS, harshEvents, maxRisk, sensitivity`)

- [ ] **Step 1: 실패하는 테스트**
  - `roundTrip`: 프로필 2개(기록·케어 통계 포함) 저장 → 불러오기 결과가 같다
  - `corruptFileGivesEmpty`: `profiles.json`에 `"{oops"` → `load()`가 빈 목록, 예외 없음
  - `missingFileGivesEmpty`
  - `atomicWriteLeavesNoTemp`: 저장 후 폴더에 `.tmp` 파일 없음
  - `guestOngoingNotPersisted`: `saveOngoing(Ongoing(profileId = null, …))` 후 `loadOngoing() == null`, 파일 없음
  - `comfortSnapshotRestores` (ComfortTest): 데모 120초 진행 → `snapshot` → 새 `Comfort`에 `restore` → `windowMsdv`, `score()`, `harshEvents`, `maxRisk` 같음
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `org.json` 사용. 쓰기는 `File(dir, name + ".tmp")`에 쓰고 `renameTo`. 파싱 실패는 빈 목록/`null`.
- [ ] **Step 4: 통과 확인** — `./gradlew testDebugUnitTest`
- [ ] **Step 5: 커밋** — `feat: 프로필 저장과 진행 중 주행 복원`

### Task 3: 즉시 케어·휴게소·회전 예고 판단 로직

**Files:**
- Create: `app/src/main/java/com/motioncare/Assist.kt`
- Test: `app/src/test/java/com/motioncare/AssistTest.kt`

**Interfaces:**
- Produces:
  - `fun isSick(text: String): Boolean` — 단어: 속, 멀미, 어지러, 토할, 메스꺼, 울렁
  - `fun answer(text: String): Boolean?` — 예(예, 네, 응, 좋아, 추가) → true, 아니오(아니, 됐어, 괜찮아, 취소) → false, 그 외 null. 아니오를 먼저 검사한다
  - `data class RestArea(val name: String, val lat: Double, val lon: Double)`
  - `fun parseRestAreas(json: String): List<RestArea>` — 형식 `[{"n":"이름","a":위도,"o":경도}]`
  - `fun nearestRestArea(lat: Double, lon: Double, areas: List<RestArea>, maxKm: Double = 50.0): Pair<RestArea, Double>?` (하버사인, km)
  - `fun shouldCueTurn(distanceM: Double, key: String, lastKey: String?): Boolean` — 30 < 거리 ≤ 300 이고 `key != lastKey`
  - `class SpeechGate(private val gapMs: Long = 30_000) { fun allow(text: String, nowMs: Long): Boolean }`

- [ ] **Step 1: 실패하는 테스트**
  - `sickPhrases`: "속이 안 좋아", "멀미 나요", "어지러워" → true / "음악 틀어줘", "" → false
  - `answers`: "네 추가해줘"→true, "아니 괜찮아"→false, "응"→true, "어?"→null
  - `nearestPicksClosest`: 서울(37.5665,126.9780)에서 [A(37.40,127.10), B(36.00,127.50)] → A, 거리 15~25km
  - `nothingWithin50Km`: 미국 좌표(37.42,-122.08) → null
  - `parseRestAreas`: 2개 항목 파싱, 잘못된 JSON → 빈 목록
  - `turnCueOncePerManeuver`: (250m, "L-용인") 처음 → true, 같은 key → false, 400m → false, 20m → false
  - `speechGate`: 같은 문장 10초 뒤 → false, 31초 뒤 → true, 다른 문장 → true
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat: 즉시 케어 판단 로직`

### Task 4: 휴게소 데이터

**Files:**
- Create: `app/src/main/res/raw/rest_areas.json`
- Modify: `README.md` (데이터 출처: © OpenStreetMap contributors, ODbL)

- [ ] **Step 1: 내려받기** — Overpass API(`https://overpass-api.de/api/interpreter`)에 대한민국 영역의 `highway=services`(node·way, 이름 있는 것, way는 `out center`) 질의.
- [ ] **Step 2: 변환** — `[{"n","a","o"}]` 형식, 좌표 소수 5자리, 같은 이름+좌표 중복 제거. 항목 수 100개 이상인지 확인.
- [ ] **Step 3: 검증** — `AssistTest`에 `bundledDataParses`: `src/main/res/raw/rest_areas.json`을 읽어 `parseRestAreas` → 100개 이상, 모든 좌표가 위도 33~39, 경도 124~132.
- [ ] **Step 4: 커밋** — `data: 고속도로 휴게소 위치 (OpenStreetMap)`

### Task 5: 디자인 시스템 (주간/야간, 공통 부품)

**Files:**
- Modify: `app/src/main/java/com/motioncare/Ui.kt`
- Create: `app/src/main/res/drawable/ic_*.xml` (선 아이콘: 사람, 추가, 마이크, 뒤로, 바람, 호흡, 회전, 시트, 휴게소)

**Interfaces:**
- Produces: `object T`의 색 값을 `T.apply(night: Boolean)`로 바꾸는 팔레트(이름은 그대로 `GROUND, SURFACE, RAISED, RULE, TEXT, MUTED, FAINT, DATA, POS/WARN/NEG(+_BG)`, 추가 `PRIMARY_BG, PRIMARY_TEXT`), `Context.button(text, primary, icon: Int? = null, onClick)` 높이 64dp, `Context.card()`, `Context.pill()`, `Context.icon(res: Int, sizeDp: Int)`.

- [ ] **Step 1: 스타일 뽑기** — `design-taste-frontend`: `draw.mjs --app --kind markets --theme light --options 3`로 주간 스타일 후보 → Pleos 시스템 화면(밝은 바탕, 둥근 카드)에 가장 가까운 것을 고르고 이유 한 줄 기록. 야간은 기존 `true-black-flat`.
- [ ] **Step 2: 토큰·부품 명세** — octo `ui-ux-designer` 에이전트에 Pleos 규칙(3장 제약)과 두 스타일을 주고 색 값(대비 4.5:1 확인 포함)·글자 크기·간격·버튼/카드/알약 명세를 받는다.
- [ ] **Step 3: 구현** — `Ui.kt`에 반영. 대비 확인: 텍스트/배경 조합마다 대비율을 계산하는 테스트 `UiContrastTest`(색 값만 쓰는 순수 계산) → 4.5 이상.
- [ ] **Step 4: 확인** — 에뮬레이터 주간/야간 모두 기존 주행 화면 스크린샷.
- [ ] **Step 5: 커밋** — `design: 주간/야간 팔레트와 공통 부품`

### Task 6: 탑승자 선택·프로필 만들기·화면 전환

**Files:**
- Create: `app/src/main/java/com/motioncare/Screens.kt` (선택, 만들기 화면)
- Modify: `app/src/main/java/com/motioncare/MainActivity.kt` (화면 전환, 뒤로가기, 운전 규제 감지)
- Modify: `AndroidManifest.xml` (`android:configChanges`로 회전 시 재생성 대신 다시 그리기)

**Interfaces:**
- Consumes: `ProfileStore`, `Profile`, `surveySensitivity`, `Ui.kt` 부품
- Produces: `MainActivity.show(screen: Screen)`, `sealed interface Screen { RiderSelect; CreateProfile; Drive; Report; ProfileDetail(id) }`, `var rider: Profile?`(null = 게스트), `val keyboardAllowed: Boolean`(`CarUxRestrictionsManager` 구독)

- [ ] **Step 1: 탑승자 선택** — 카드(별칭, 자리, 최근 점수, 마지막 사용 순), "게스트로 시작", "새 프로필". 카드 길게 누름 → 프로필 상세.
- [ ] **Step 2: 프로필 만들기** — 별칭(최대 10자, 빈 값 불가) → 자리 3개 중 선택 → 설문 3문항(각 "아니요/가끔/자주") → 저장. `keyboardAllowed == false`면 화면 대신 "주행 중에는 프로필을 만들 수 없어요 · 정차 후 다시 눌러주세요".
- [ ] **Step 3: 확인** — 에뮬레이터: 만들기/선택/뒤로가기, D기어에서 차단(Review Focus 3), 회전(세로↔가로) 후 입력 유지.
- [ ] **Step 4: 커밋** — `feat: 탑승자 선택과 프로필 만들기`

### Task 7: 주행 화면 — 즉시 케어, 음성, 휴게소, 내비 회전 예고, 프로필 반영

**Files:**
- Modify: `MainActivity.kt`, `Screens.kt`(주행 화면 부분 이동), `app/build.gradle.kts`(SDK 2개), `AndroidManifest.xml`(권한 3개)

**Interfaces:**
- Consumes: `isSick`, `answer`, `nearestRestArea`, `shouldCueTurn`, `SpeechGate`, `Profile.cares`, `Profile.careTempC`, `Profile.seat`
- Produces: `fun sickNow()`, `fun offerRestArea(reason: String)`, 주행 중 실행된 케어 집합 `caresThisTrip: MutableSet<Care>`

- [ ] **Step 1: "속이 안 좋아요" 버튼** — 주행 화면 맨 아래 가로 전체, 높이 80dp. 누르면 `sickNow()`: 공기 케어 + 호흡 가이드(꺼진 케어는 건너뜀) + 음성 + `offerRestArea("sick")`.
- [ ] **Step 2: 마이크 버튼** — STT `ON_DEVICE`. 최종 결과(`completed == true`)가 `isSick`이면 `sickNow()`. 휴게소 질문 중이면 `answer` 결과로 처리. 오류·무응답 8초 → "잘 못 들었어요 · 버튼을 눌러주세요" (Review Focus 4).
- [ ] **Step 3: 휴게소 제안** — FusedLocation 마지막 위치 → `nearestRestArea`. 결과 있으면 카드 "○○휴게소 · 12km · 경유지로 추가할까요?" [추가][괜찮아요] + 같은 질문 음성. 추가 → `NaviHelper.addWaypoint(RequestWaypointInfo(lat, lon, name, WaypointIndex.FIRST, poiId = "", address = "", poiSubId = ""))`. 없음/내비 실패 → 한 줄 안내 (Review Focus 5). 위험 단계 3분 연속이면 주행당 한 번 `offerRestArea("high")`.
- [ ] **Step 4: 내비 회전 예고** — `onTBTInfo` 첫 항목이 좌·우·급회전·유턴 계열이면 `shouldCueTurn(distance, "${type}-${description}", lastKey)` → "300m 뒤 왼쪽으로 돌아요". 경로 안내 중에는 방향지시등 예고를 끈다.
- [ ] **Step 5: 프로필 반영** — 경고 민감도 = `rider?.sensitivity ?: 1.0`, 공기 케어 온도 = `careTempC`, 꺼진 케어 건너뜀, 좌석 구역 = `seat`(지원 안 하면 등받이 버튼 숨김). 사용자가 직접 바꾼 온도(앱이 보낸 값과 ±0.1 이상 다름)를 `learnTemp`로 기억.
- [ ] **Step 6: 음성 겹침 방지** — 모든 안내를 `SpeechGate` 통과 후 재생.
- [ ] **Step 7: 확인** — 에뮬레이터: 버튼 흐름, 음성 흐름(마이크), 위치를 국내 고속도로로 설정 후 경유지 추가, 미국 위치에서 "근처 휴게소 없음", 내비 경로 설정 후 회전 예고.
- [ ] **Step 8: 커밋** — `feat: 즉시 케어, 휴게소 경유, 내비 회전 예고`

### Task 8: 주행 리포트·프로필 상세·게스트 저장

**Files:**
- Modify: `Screens.kt`, `MainActivity.kt`, `Ui.kt`(작은 막대 그래프 `TrendView`)

**Interfaces:**
- Consumes: `Trip`, `Profile.learn`, `Profile.enableCare`, `Profile.earlierPercent`, `ProfileStore`

- [ ] **Step 1: 리포트** — 점수, 멀미 0~10, "도움 된 케어"(이번에 실행된 케어만 칩으로, 선택 사항), 학습 결과 한 줄("다음부터 경고가 10% 일찍 와요"), 택시 전송 미리보기. [완료]를 누르면 `learn` → 저장.
- [ ] **Step 2: 게스트 저장 제안** — 게스트면 "프로필로 저장"(키보드 허용 시만) → 프로필 만들기 화면에 이번 기록을 넘겨 첫 기록으로 저장.
- [ ] **Step 3: 프로필 상세** — 최근 10회 점수 막대 + 멀미 점, 경고 시점(`earlierPercent`), 케어별 "도움 n/m" 과 켜기/끄기, 선호 온도, 삭제(확인 대화상자, 별칭 표시).
- [ ] **Step 4: 확인** — 에뮬레이터: 리포트 → 학습 반영 → 상세에서 확인, 케어 자동 꺼짐·다시 켜기, 삭제, 게스트 기록이 남지 않음(Review Focus 2).
- [ ] **Step 5: 커밋** — `feat: 주행 리포트와 프로필 상세`

### Task 9: 생명주기·실패 대비

**Files:** `MainActivity.kt`

- [ ] **Step 1** — `onStop`: 화면 갱신 중단, `saveOngoing`. `onStart`: 갱신 재개. 30초마다 `saveOngoing`. 앱 시작 시 `loadOngoing()`이 있으면 "진행 중이던 주행을 이어갈까요?" [이어하기][새로 시작].
- [ ] **Step 2** — 모든 SDK 콜백을 메인 스레드로 넘긴다(`ui.post`). SDK 초기화 실패는 해당 카드 한 줄 안내.
- [ ] **Step 3: 확인** — 주행 중 홈으로 나갔다 오기, `adb shell am kill` 후 다시 실행 → 이어하기, 에뮬레이터 CPU(`dumpsys cpuinfo`) 주행 중 5% 미만.
- [ ] **Step 4: 커밋** — `fix: 백그라운드와 재시작 대응`

### Task 10: 디자인 비평 반복

- [ ] **Step 1** — 화면 5개 × 주간/야간 × 세로/가로 스크린샷.
- [ ] **Step 2** — `design-taste-frontend` 앱 화면 비평 7항목 + Pleos 규칙(18px, 4.5:1, 3줄, 64dp)으로 점검하고 octo `ui-ux-designer`에게 같은 스크린샷 검토를 받는다.
- [ ] **Step 3** — 문제 상위 3개 수정 → 다시 찍기. 최대 3회.
- [ ] **Step 4: 커밋** — `design: 비평 반영`

### Task 11: 최종 검증·배포·제출물 갱신

- [ ] **Step 1** — `./gradlew testDebugUnitTest assembleRelease` 전부 통과, `apksigner verify` v3.
- [ ] **Step 2** — 설계 8장의 에뮬레이터 확인 목록 13개를 release APK로 다시 확인하고 결과를 표로 기록.
- [ ] **Step 3** — `apk/MotionCare_멀미케어_release.apk` 교체, README 갱신, 커밋, `git push`.
- [ ] **Step 4** — 시연 영상 재녹화(프로필 선택 → 주행 → 속이 안 좋아요 → 휴게소 추가 → 리포트·학습), 기획서 3·5번 "구현 완료"로 갱신.
