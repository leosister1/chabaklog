# 차박로그

차가 직접 평가하는 차박지 지도. Pleos Connect(AAOS) 차량용 앱.

밤새 배터리가 얼마나 줄었는지, 주차가 얼마나 어려웠는지를 차량 신호로 자동 기록하고, 장소별로 모아 다음 차박을 고를 때 보여 준다.

## 구조

```
app/          Android 앱 (Compose, Room)
  adapters/   Pleos Vehicle SDK · AAOS CarPropertyManager · 위치 구현
  data/       로컬 DB (세션, 장소)
  ui/         화면: 홈 · 지도 · 기록 · 아침 요약 · 데이터 출처
datasource/   순수 Kotlin 데이터 소스 추상화 (Android 없이 테스트)
```

## 실행 방법

- Android Studio, JDK 17
- Pleos Connect Emulator v2.1.0 (API 34) AVD, cold boot
- Pleos Connect Virtual Console: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/
- 실행 설정 `app` → Run

```
./gradlew :datasource:test      # 데이터 소스 추상화 테스트
./gradlew :app:assembleDebug
```

## 테스트 환경

| 항목 | 버전 |
| --- | --- |
| Pleos Connect Emulator | v2.1.0, Android 14 (API 34) |
| Vehicle SDK | 2.3.2 |
| NaviHelper SDK | 3.0.5 |
| Android Gradle Plugin / Kotlin | 8.8.2 / 2.0.0 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 28 |

## 사용하는 차량 데이터 (읽기 전용)

| 신호 | 경로 |
| --- | --- |
| 기어 | AAOS `GEAR_SELECTION` |
| 외기 온도 | AAOS `ENV_OUTSIDE_TEMPERATURE` |
| 좌석 점유, 도어, 배터리 SOC, 공조, 조향각 | Pleos Vehicle SDK |
| 목적지·경유지, 현재 위치 | Pleos NaviHelper SDK |

지원되지 않는 신호가 있으면 대체 경로로 자동 전환한다 (위치: AAOS → 내비 → 지도 선택, 외기 온도: 차량 → 날씨 API → 내장 월별 평균).
