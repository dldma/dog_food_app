# ATmega128A firmware bundle - Microchip Studio conversion / Stage 5

이 폴더를 그대로 `C:\github\dog_food_app\firmware\ATmega128A` 위치에 넣어 사용하면 된다.

## 폴더 구성

```text
ATmega128A/
├─ codevision_original/
│  ├─ atmega128.c
│  ├─ atmega128.prj
│  └─ atmega128.cwp
├─ microchip_studio/
│  └─ DogFood_ATmega128A/
│     ├─ DogFood_ATmega128A.cproj
│     ├─ main.c
│     ├─ lcd_compat.c
│     ├─ lcd_compat.h
│     └─ config.h
└─ docs/
   └─ BLUETOOTH_PROTOCOL_STAGE5.md
```

## Microchip Studio에서 열기

1. Microchip Studio 실행
2. `File -> Open -> Project/Solution`
3. `microchip_studio\DogFood_ATmega128A\DogFood_ATmega128A.cproj` 선택
4. 프로젝트 변환/업그레이드 안내가 뜨면 허용
5. `Build -> Build Solution (F7)`
6. 실제 사용 중인 programmer/debugger를 Project Properties의 Tool에서 선택
7. 빌드 성공 후 ATmega128A에 프로그램

## 변환 내용

- CodeVision `mega128.h` -> AVR-GCC `avr/io.h`
- CodeVision `interrupt [...]` -> AVR-GCC `ISR(...)`
- `PORTE.2` 같은 비트 문법 -> `_BV(PE2)` 비트 연산
- CodeVision `delay_ms/us` -> `util/delay.h`
- CodeVision 기본 `lcd.h` -> 동일 PORTC 핀 배치를 사용하는 `lcd_compat.c`
- 원본 UART0 9600 / UART1 115200, HX711, PN532, 모터, 자동 급수, 18자리 D 패킷 유지
- Timer1 1Hz 시스템 시계와 매일 반복 예약 기능 추가

## LCD 핀 배치

CodeVision 원본의 `__lcd_port=PORTC` 표준 배치를 그대로 구현했다.

- PC0 -> RS
- PC1 -> RW
- PC2 -> EN
- PC4 -> DB4
- PC5 -> DB5
- PC6 -> DB6
- PC7 -> DB7

## 중요한 제한

RTC 부품을 추가하지 않는 방식이므로 전원이 꺼지면 현재 시간이 사라진다. 전원을 다시 켠 후 Android 앱이 Bluetooth로 연결되면 Stage 5 프로토콜로 현재 시간과 모든 예약을 다시 전송해야 한다.

현재 Android Stage 4 앱은 새 시간/예약 명령을 아직 보내지 않는다. 기존 수동/시연 기능은 계속 호환되며, 다음 Android Stage에서 자동 동기화를 연결하면 생활모드가 완성된다.

## 검증 관련

이 프로젝트는 제공된 CodeVisionAVR 원본을 기반으로 AVR-GCC 문법으로 변환했다. 현재 작업 환경에는 Microchip Studio/AVR-GCC 하드웨어 툴체인이 없어 실제 ATmega128A 타깃 빌드 및 실기기 다운로드까지 직접 검증할 수는 없다. 첫 Microchip Studio 빌드에서 장치 팩/도구 버전에 따른 메시지가 뜨면 그 오류를 기준으로 바로 조정하면 된다.

## 사료 끼임 자동 복구

Microchip Studio 펌웨어에는 사료 배출 중 끼임 자동 복구가 적용되어 있습니다.

- 사료 모터 정방향 동작 중 500ms 간격으로 무게 변화를 확인합니다.
- 약 2초 동안 사료 무게가 2g 이상 증가하지 않으면 끼임으로 판단합니다.
- 모터를 150ms 정지한 뒤 400ms 역회전하고, 다시 150ms 정지한 후 정방향 급식을 재개합니다.
- 같은 급식에서 최대 3회까지 자동 복구를 시도합니다.
- 3회 복구 후에도 다시 막히면 모터 보호를 위해 정지하고 FOOD LED를 켭니다.
- 시간/민감도는 `config.h`의 `FOOD_JAM_*` 값으로 조절할 수 있습니다.
