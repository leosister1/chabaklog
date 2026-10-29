# 차박로그

**차가 직접 평가하는 차박지 지도.** Pleos Connect(AAOS) 차량용 앱.

사람의 후기 대신 차량 신호로 밤을 기록한다. 기어·문 신호로 차박을 스스로 알아채고, 밤새 배터리가 얼마나 줄었는지와 주차가 얼마나 어려웠는지를 남긴다. 이 기록과 오늘 밤 기온으로 "이 캠핑장에서 오늘 자면 배터리를 얼마나 쓸지"를 예측하고, 캠핑장 근처 공중화장실을 차량 내비 경유지로 넣어 준다.

## 주요 기능

| 화면 | 하는 일 |
| --- | --- |
| 홈 | 지금 차 상태(기어·배터리·외기 온도), 오늘 밤 예상 소모, 직접 시작/종료 버튼, 시연 모드 |
| 지도 | 공공 우수야영장 55곳 + 내 차박지 + 시연용 기록. 장소 카드: 오늘 밤 예상 배터리 사용, 주차 난이도, 기록 수, 목적지로 설정, 근처 화장실 경유지 |
| 기록 | 지난 차박 목록 (배터리 변화, 주차 난이도, 문 열림, 공조 시간) |
| 아침 요약 | 가장 최근에 끝난 밤 한 장 요약 |
| 데이터 출처 | 위치·외기 온도·차박 감지·주차 난이도를 지금 어떤 경로로 받는지 |

주행 중(D)에는 CarUxRestrictions에 따라 "주행 중 · 배터리 N%" 간단 화면만 보인다.

## 실행 방법

### 설치된 APK로 보기

1. Pleos Connect Emulator v2.1.0 (Android 14, API 34) AVD를 **Cold Boot**로 켠다.
2. `adb install -r apk/김유진_차박로그_release.apk`
3. 앱 목록에서 **차박로그**를 연다. 위치 권한 요청은 허용한다.

### 소스로 빌드

- Android Studio (JDK 17), 실행 설정 `app` → Run
- Pleos Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 SDK를 받는다 (`settings.gradle.kts`에 설정됨).

```
./gradlew :datasource:test        # 데이터 소스 추상화 테스트 (Android 없이)
./gradlew :app:assembleDebug      # 개발용 (데이터 출처 화면에 연결 시험 표시)
./gradlew :app:assembleRelease    # 제출용 → app/build/outputs/apk/release/app-release.apk
```

release 서명 키는 저장소에 넣지 않는다. 루트에 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)가 있으면 그 키로, 없으면 debug 키로 서명한다.

## 시연 방법 (Virtual Console)

1. `adb forward tcp:8088 tcp:8088` 후 브라우저에서 http://127.0.0.1:8088/ → **Emulation Controls**
2. 앱 홈에서 **시연 모드**를 켠다 (차박 감지 대기 시간 30분 → 1분).
3. 지도에서 캠핑장(예: 자라섬 캠핑장)을 누르고 **목적지로 설정**, 근처 화장실 **경유지로**.
4. Virtual Console: Gear **D** → **R** ↔ **D** 몇 번(후진 주차) → **P**.
5. P에서 문을 닫은 채 **1~2분 기다린다** (30초마다 확인하므로 최대 1분 30초). 홈에 "차박 중"이 뜬 뒤에 Door를 열었다 닫고 Battery SOC 슬라이더를 낮춘다. 차박이 시작되기 전에 문을 열면 대기 시간이 처음부터 다시 시작된다. (슬라이더는 Tab 키로 포커스를 빼야 값이 전송됨)
6. Gear **D** → 세션 종료. **아침 요약** 화면에서 지난 밤 기록 확인.
7. 영상용 예시가 필요하면 데이터 출처 → **시연용 기록 넣기** (화면에 "시연용" 표시, 예측에는 쓰지 않음).

## 테스트 환경

| 항목 | 버전 |
| --- | --- |
| Pleos Connect Emulator | v2.1.0, Android 14 (API 34), 그래픽 Software(SwiftShader) |
| Vehicle SDK | 2.3.2 |
| NaviHelper SDK | 3.0.5 |
| Android Gradle Plugin / Kotlin | 8.8.2 / 2.0.0 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 28 |

macOS(Apple Silicon)에서 그래픽을 Hardware로 두면 내비 경로 화면에서 에뮬레이터가 종료되는 경우가 있어 Software로 시험했다.

## 사용하는 차량 데이터 (읽기 전용, 차량을 제어하지 않음)

| 신호 | 경로 | 쓰는 곳 |
| --- | --- | --- |
| 기어 | AAOS `GEAR_SELECTION` | 주행/주차 판단, 후진 횟수 |
| 외기 온도 | AAOS `ENV_OUTSIDE_TEMPERATURE` | 예측 |
| 도어, 좌석 점유 | Pleos Vehicle SDK | 차박 감지, 문 열림 횟수 |
| 배터리 SOC, 공조 전원 | Pleos Vehicle SDK | 밤의 기록 |
| 조향각 | Pleos Vehicle SDK | 주차 난이도 |
| 현재 위치, 목적지·경유지 | Pleos NaviHelper SDK | 장소 연결, 길안내 |

지원되지 않는 신호가 있으면 대체 경로로 자동 전환한다.

| 항목 | 순서 |
| --- | --- |
| 위치 | 내비 현재 위치 → AAOS 위치 → 내비 목적지 → 지도에서 직접 선택 (한국 밖 좌표는 버림) |
| 외기 온도 | 차량 센서 → 날씨 예보(Open-Meteo) → 내장 기상청 평년 월별 최저기온 |
| 차박 감지 | 기어 P + 문 패턴 → 기어 P + 좌석 점유 → 직접 시작 버튼 |
| 주차 난이도 | 후진 횟수 + 조향각 → 후진 횟수만 |

## 앱에 내장한 데이터

| 데이터 | 출처 |
| --- | --- |
| 공공 우수야영장 55곳 (2023~2025) | 문화체육관광부·한국관광공사 고캠핑, 공공데이터포털 (이용허락범위 제한 없음) |
| 캠핑장 근처 공중화장실 74곳 | 행정안전부 전국공중화장실표준데이터, 좌표 © OpenStreetMap contributors (ODbL) |
| 월별 평균 최저기온 13개 지점 | 기상청 1991~2020 평년값 |
| 한국 지도 윤곽 | Natural Earth (퍼블릭 도메인) |

## 구조

```
app/          Android 앱 (Compose, Room)
  adapters/   Pleos Vehicle SDK · AAOS CarPropertyManager · NaviHelper · 위치 · 날씨
  data/       로컬 DB (세션, 장소), 내장 데이터 읽기
  tracking/   차박 세션 상태 머신, 예측
  ui/         화면: 홈 · 지도 · 기록 · 아침 요약 · 데이터 출처
datasource/   순수 Kotlin 데이터 소스 추상화·예측 (Android 없이 테스트)
apk/          제출용 release APK (김유진_차박로그_release.apk)
```
