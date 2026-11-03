# NFC 반영 전체 프로젝트 — 시작 안내

## 이 ZIP으로 할 수 있는 작업

이 압축파일에는 Android Studio 프로젝트 **전체 소스**와 ATmega128A 펌웨어가 함께 들어 있습니다.
- `app/`, `gradle/`, `gradlew`, `build.gradle.kts`, `settings.gradle.kts` 등 Android Studio 프로젝트 필수 파일 포함
- `firmware/ATmega128A/.../main.c`: NFC 태그 구분 및 덮개 제어 수정본 포함
- 기존 다른 화면, 블루투스, 급식/급수/투약 관련 파일 유지

**중요:** Android Studio 앱을 빌드하거나 스마트폰에 설치하는 것만으로 ATmega128A에 수정된 NFC 펌웨어가 적용되지는 않습니다. MCU용 펌웨어는 Microchip Studio에서 별도로 빌드·업로드해야 합니다.

## 1. Android Studio에 프로젝트 통째로 넣기

1. ZIP을 압축 해제합니다. 안에 `dog_food_app` 폴더가 있습니다.
2. Android Studio → **Open** → 압축 해제한 `dog_food_app` 폴더를 선택합니다.
3. Gradle Sync가 끝나면 휴대폰에서 기존 앱을 실행합니다.
4. 처음 여는 PC의 Android SDK 경로는 Android Studio에서 새로 설정합니다. 원본 PC의 `local.properties`는 포함하지 않았습니다.

## 2. NFC 덮개 제어 펌웨어 반영하기

1. Microchip Studio → **File > Open > Project/Solution**
2. 아래 파일을 엽니다.
   `firmware/ATmega128A/microchip_studio/DogFood_ATmega128A/DogFood_ATmega128A.atsln`
3. `main.c`가 이미 NFC 수정본으로 들어 있습니다. **다시 붙여넣을 필요가 없습니다.**
4. 빌드 후 실제 ATmega128A에 업로드합니다.
5. PN532의 2개 태그를 인식시키고 덮개 동작을 점검합니다.

## 3. NFC 동작

- 원본 코드 기준 태그값 `0xA7` → 덮개 열기
- 원본 코드 기준 태그값 `0x99` → 덮개 닫기
- NFC 태그 인식 시 자동 모드(`start_mode`)와 무관하게 덮개 명령 1회 실행
- 계속 같은 태그가 읽힐 때 중복 구동 방지
- `NFC_LID_MOVE_SECONDS`(현재 기본값 5초)로 구동 후 모터 정지
- 기존 블루투스 상태값과 Android 앱의 덮개 표시 연동 유지

## 4. 테스트 전 주의

- **5초는 임시 모터 구동 시간**입니다. 실제 기구부에 맞는 시간으로 조정하고 무리한 회전/과열을 방지하세요. 리미트 스위치나 과전류 보호가 있는 편이 안전합니다.
- `0xA7` / `0x99`는 원래 펌웨어의 NFC 응답 파싱 값이며, 전체 UID 검증이 아닙니다. 현물 태그를 반드시 시험하세요.
- 화면의 열림·닫힘은 **명령/제어 상태**이며 실제 덮개 위치를 측정하는 센서 결과는 아닙니다.
- 원본 프로젝트는 보존하세요. 이 수정본은 코드 정적 검토를 거쳤으나 Android APK 및 ATmega128A 실제 빌드·실기기 시험을 마친 제품은 아닙니다.
- 자세한 기술 변경 내역: `docs/NFC_TAG_INTEGRATION.md`.

## 파일을 어디서 고치나요?

`dog_food_app/firmware/ATmega128A/microchip_studio/DogFood_ATmega128A/main.c`

상단에서 `NFC_LID_MOVE_SECONDS` 검색 → 구동시간 조정
`ISR(USART1_RX_vect)` → 태그 식별/명령
`main()`의 NFC 처리 → 실제 모터 구동
`ISR(TIMER1_COMPA_vect)` → 자동 정지
