# 멀미케어 (MotionCare)

멀미가 오기 전에 차가 먼저 챙겨주는 동승자 케어 + 동승자 편안함 점수.

## 주요 기능
- **탑승자 프로필**: 게스트로 바로 타거나, 별칭·자리·설문 3문항으로 프로필을 만들면 경고 시점·케어 효과·선호 온도를 주행마다 학습 (주행 중에는 프로필 생성 차단)
- **자동 케어**: 멀미 위험 지수(ISO 2631-1)가 주의 단계면 외기·풍량·온도(현재보다 1~2°C 낮게), 위험 단계면 호흡 가이드(분당 7.5회, 앰비언트 조명 + 짧은 소리로 박자, 흔들림이 이어지는 동안 5~15분)
- **"속이 안 좋아요"** 버튼·음성: 즉시 케어 + 가장 가까운 휴게소를 내비 경유지로 추가 (위험 단계 3분 지속 시 자동 제안)
- **회전 예고**: 회전 약 4초 전(내비 경로 안내 중) 또는 방향지시등이 켜질 때 짧은 소리와 "곧 왼쪽/곧 오른쪽"
- **운전자 안내**: 급가속·급제동·급회전을 모아 두었다가 정차했을 때(또는 주행 종료 때) 한 번 음성으로
- **주행 리포트**: 편안함 점수·멀미 응답·도움 된 케어 → 학습 결과 한 줄, 택시 플랫폼 전송 미리보기
- 주간/야간 테마, 화면 회전·백그라운드 대응, 앱이 꺼져도 진행 중 주행 이어하기

## 실행 방법
1. Android Studio 최신 버전 설치 (JDK 17 포함)
2. SDK Manager → SDK Update Sites에 Pleos Connect System Image 추가 (**PC 종류에 맞는 주소를 써야 함**)
   - Mac (Apple 칩): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Windows·Linux (Intel/AMD): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - Windows에서 Mac 주소(arm64)를 쓰면 에뮬레이터가 극도로 느려져 노트북이 멈춘 것처럼 됨. 이미 만든 AVD가 있으면 지우고 다시 만들기
3. Device Manager → Automotive → Pleos Connect v2.1.0 (API 34, RAM 4GB, Cold boot) AVD 생성·실행
   - Windows: "Windows 기능 켜기/끄기"에서 **Windows 하이퍼바이저 플랫폼**을 켜고 재부팅 (꺼져 있으면 에뮬레이터가 매우 느림)
   - 노트북 RAM이 8GB면 AVD RAM을 2~3GB로 낮추고 다른 프로그램을 끈 채 실행 (에뮬레이터 + Android Studio + Gradle이 함께 8GB 넘게 씀)
   - 빌드 없이 앱만 확인하려면 4번 대신 에뮬레이터를 켠 뒤 아래 [APK](#apk) 설치 명령만 실행
4. 이 폴더를 Android Studio로 열고 Run
5. (공조 시연 시) `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088 Virtual Console의 Climate에서 풍량을 한 번 조작
   - 에뮬레이터를 막 켠 상태에서는 공조 시뮬레이션이 아직 동작하지 않아서 풍량 변경이 "사용 불가"로 거부됨
6. 앱에서 **게스트로 시작** 또는 **새 프로필** → **데모 주행** → 4배속 가상 주행으로 위험 지수 상승, 공기 케어, 호흡 가이드, 휴게소 제안, 주행 리포트 확인
7. (휴게소 시연 시) `adb emu geo fix 127.18 37.27`로 위치를 고속도로 근처로 둔 뒤 **속이 안 좋아요**

## 테스트 환경
- Pleos Connect Emulator 2.1.0 (Android 14), compileSdk 35, minSdk 28
- Vehicle SDK 2.3.2, FusedLocation SDK 1.1.3, TextToSpeech SDK 3.3.1, SpeechToText SDK 3.3.1, NaviHelper SDK 3.0.5
- 단위 테스트: `./gradlew testDebugUnitTest` 61개 (위험 지수·점수, 프로필 학습·저장, 케어 효과 학습, 호흡 박자·연장, 공기 케어 온도, 음성 판정, 휴게소 거리, 회전 예고 시점, 운전자 안내, 화면 대비)
- 에뮬레이터 확인 결과: [docs/superpowers/2026-11-02-motioncare-v2-verification.md](docs/superpowers/2026-11-02-motioncare-v2-verification.md)

## 사용 데이터·제어
| 기능 | API |
|---|---|
| 속도 | Android Automotive `PERF_VEHICLE_SPEED` |
| 커브(좌우 흔들림) | Vehicle `SteeringWheel` 핸들 각도 (없으면 FusedLocation `heading`) |
| 방향지시등 | Vehicle `TurnSignal` |
| 외기·풍량·온도 | Vehicle `HVAC` |
| 호흡 가이드 조명 | Vehicle `Light` (앰비언트 밝기) + Android `ToneGenerator`(박자 소리) |
| 등받이 | Vehicle `Seat` (P단에서만) |
| 선호 온도 학습 | Vehicle `HVAC` 온도 변경 알림 |
| 음성 안내 | TextToSpeech (ON_DEVICE) |
| "속이 안 좋아요" 음성 | SpeechToText (ON_DEVICE) |
| 휴게소 경유지·회전 예고 | NaviHelper (`addWaypoint`, `requestRoute`, `onTBTInfo`) |
| 휴게소 위치 | FusedLocation 위도·경도 + 내장 휴게소 데이터 |
| 주행 중 입력 제한 | CarUxRestrictions (키보드 금지) |

## APK
[`apk/MotionCare_멀미케어_release.apk`](apk/MotionCare_멀미케어_release.apk) — v3 서명(키 교체 지원), Pleos Connect Emulator 2.1.0에서 실행 확인

설치: `adb install -g apk/MotionCare_멀미케어_release.apk` (`-g`로 차량·위치 권한을 함께 허용)

직접 release 빌드하려면 프로젝트 루트에 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)를 두고 `./gradlew assembleRelease`. 서명 키는 저장소에 포함되지 않습니다.

## 데이터 출처
- 휴게소 위치(`app/src/main/res/raw/rest_areas.json`): © OpenStreetMap contributors, [ODbL](https://opendatacommons.org/licenses/odbl/). Overpass API로 대한민국 `highway=services` 중 이름에 "휴게소"·"쉼터"가 들어간 670곳 (2026-11-02 기준).
