# Stage 5 - 생활 모드 / ATmega 시간 동기화

## 추가 기능

- 생활 자동 예약 화면 추가
- 매일 동일한 시간 반복 예약
- 예약 최대 10개
- 각 예약마다 사료 g / 약 A 개수 / 약 B 개수 설정
- 생활 모드 ON/OFF
- Bluetooth 연결 직후 휴대폰 현재 시간 자동 전송
- 저장된 생활 예약 전체 자동 동기화
- 수동 `지금 동기화` 버튼 추가
- 장치 전원 재인가 후 앱을 한 번 연결하면 시간/예약 복구
- 홈 화면에 생활 모드 상태 / 예약 시간 / 마지막 전송 시각 표시
- 다음 급식 카드에서 생활 모드의 다음 예약 표시
- 시연 모드 시작 시 생활 예약을 임시 중지하여 겹침 방지
- 시연 종료/초기화 후 생활 모드가 켜져 있으면 자동 재동기화

## Stage 5 Bluetooth 명령

- `T<HHMMSS>\n` : ATmega 현재 시간 설정
- `R\n` : 장치의 생활 예약 초기화
- `S<HHMM>x<food>y<A>z<B>\n` : 일일 예약 추가
- `E\n` : 생활 예약 활성화 + 자동모드 ON
- `X\n` : 생활 예약 일시 중지

예시:

```text
T073012
R
S0800x65y1z0
S1400x60y0z1
S2000x65y1z1
E
```

## 수정/추가 파일

- `app/src/main/java/com/example/dogfood/MainActivity.kt`
- `app/src/main/java/com/example/dogfood/DogFoodProtocol.kt`
- `app/src/main/java/com/example/dogfood/FeedingModels.kt`
- `app/src/main/java/com/example/dogfood/Prefs.kt`
- `app/src/main/java/com/example/dogfood/LifeScheduleActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_life_schedule.xml`
- `app/src/main/res/layout/item_daily_schedule.xml`
- `app/src/main/res/layout/dialog_life_schedule_edit.xml`
- `app/src/main/AndroidManifest.xml`

## 테스트 순서

1. Stage 5 Microchip Studio 펌웨어를 ATmega128A에 업로드
2. Android 앱 실행
3. `생활 자동 예약` -> `예약 관리`
4. 예약 1개 이상 추가 후 생활 모드 ON -> 저장
5. Bluetooth 장치 연결
6. 홈의 `마지막 장치 전송` 시간이 갱신되는지 확인
7. 빠른 테스트는 현재 시각보다 2~3분 뒤 예약을 만들어 확인
8. Bluetooth 연결을 끊은 뒤에도 다음 예약이 ATmega에서 실행되는지 확인

## 주의

RTC를 추가하지 않았으므로 ATmega 전원이 꺼지면 내부 시간이 사라집니다.
전원을 다시 켠 뒤에는 앱과 Bluetooth로 한 번 연결해야 합니다.
