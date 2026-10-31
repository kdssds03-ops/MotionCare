# 멀미케어 (MotionCare)

멀미가 오기 전에 차가 먼저 챙겨주는 동승자 케어 + 동승자 편안함 점수.

## 실행 방법
1. Android Studio 최신 버전 설치 (JDK 17 포함)
2. SDK Manager → SDK Update Sites에 Pleos Connect System Image 추가
   - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
3. Device Manager → Automotive → Pleos Connect v2.1.0 (API 34, RAM 4GB, Cold boot) AVD 생성·실행
4. 이 폴더를 Android Studio로 열고 Run
5. (공조 시연 시) `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088 Virtual Console의 Climate에서 풍량을 한 번 조작
   - 에뮬레이터를 막 켠 상태에서는 공조 시뮬레이션이 아직 동작하지 않아서 풍량 변경이 "사용 불가"로 거부됨
6. 앱에서 **데모 주행** → 4배속 가상 주행으로 위험 지수 상승, 공기 케어, 호흡 가이드, 점수 리포트 확인

## 테스트 환경
- Pleos Connect Emulator 2.1.0 (Android 14), compileSdk 35, minSdk 28
- Vehicle SDK 2.3.2, FusedLocation SDK 1.1.3, TextToSpeech SDK 3.3.1
- 단위 테스트: `./gradlew test` (멀미 위험 지수·점수 계산)

## 사용 데이터·제어
| 기능 | API |
|---|---|
| 속도 | Android Automotive `PERF_VEHICLE_SPEED` |
| 커브(좌우 흔들림) | Vehicle `SteeringWheel` 핸들 각도 (없으면 FusedLocation `heading`) |
| 방향지시등 | Vehicle `TurnSignal` |
| 외기·풍량·온도 | Vehicle `HVAC` |
| 호흡 가이드 조명 | Vehicle `Light` (앰비언트 밝기) |
| 등받이 | Vehicle `Seat` |
| 음성 안내 | TextToSpeech (ON_DEVICE) |

## APK
[`apk/MotionCare_멀미케어_release.apk`](apk/MotionCare_멀미케어_release.apk) — v3 서명(키 교체 지원), Pleos Connect Emulator 2.1.0에서 실행 확인

설치: `adb install -g apk/MotionCare_멀미케어_release.apk` (`-g`로 차량·위치 권한을 함께 허용)

직접 release 빌드하려면 프로젝트 루트에 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)를 두고 `./gradlew assembleRelease`. 서명 키는 저장소에 포함되지 않습니다.
