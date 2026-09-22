#ifndef DOGFOOD_CONFIG_H_
#define DOGFOOD_CONFIG_H_

#ifndef F_CPU
#define F_CPU 16000000UL
#endif

// 생활 모드에서 저장할 수 있는 일일 예약 최대 개수.
// 필요하면 5, 10, 15 등으로 변경 가능하다.
#define MAX_DAILY_SCHEDULES 10

// 기존 하드웨어/보정값 유지
#define WATER_SCALE 1038.0f
#define FOOD_SCALE 1076.0f
#define DEFAULT_WATER_TARGET_G 100
#define WATER_REFILL_GAP_G 30
#define DEFAULT_FOOD_TARGET_G 50
#define DEFAULT_FOOD_LEVEL_THRESHOLD 290

// 사료 끼임 자동 복구 설정
// 500ms 간격으로 4회(약 2초) 동안 무게가 2g 이상 늘지 않으면 끼임으로 판단한다.
#define FOOD_JAM_CHECK_INTERVAL_MS 500
#define FOOD_JAM_STAGNANT_CHECKS 4
#define FOOD_JAM_MIN_PROGRESS_G 2

// 끼임 감지 시: 정지 -> 0.4초 역회전 -> 정지 -> 정회전 재시도
#define FOOD_JAM_STOP_BEFORE_REVERSE_MS 150
#define FOOD_JAM_REVERSE_MS 400
#define FOOD_JAM_STOP_AFTER_REVERSE_MS 150
#define FOOD_JAM_MAX_RECOVERY 3

#define PILL_CAPACITY 7

#endif
