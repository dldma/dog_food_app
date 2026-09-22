# Stage 14 - 사료 끼임 자동 복구

## 동작

사료 급식 중 사료그릇 무게가 증가하는지 확인한다.

- 500ms 간격으로 무게 확인
- 약 2초 동안 2g 이상 증가하지 않으면 끼임으로 판단
- 모터 150ms 정지
- 사료 모터 400ms 역회전
- 모터 150ms 정지
- 다시 정방향으로 급식 재개
- 한 번의 급식에서 최대 3회 자동 복구
- 3회 복구 후에도 다시 막히면 모터 보호를 위해 정지하고 FOOD LED ON

## 설정 위치

`firmware/ATmega128A/microchip_studio/DogFood_ATmega128A/config.h`

- `FOOD_JAM_CHECK_INTERVAL_MS`
- `FOOD_JAM_STAGNANT_CHECKS`
- `FOOD_JAM_MIN_PROGRESS_G`
- `FOOD_JAM_STOP_BEFORE_REVERSE_MS`
- `FOOD_JAM_REVERSE_MS`
- `FOOD_JAM_STOP_AFTER_REVERSE_MS`
- `FOOD_JAM_MAX_RECOVERY`

## 모터 방향

- 정방향: PE4=LOW, PE5=HIGH
- 역방향: PE4=HIGH, PE5=LOW
