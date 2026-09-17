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
#define DEFAULT_FOOD_TARGET_G 50
#define DEFAULT_FOOD_LEVEL_THRESHOLD 290
#define PILL_CAPACITY 7

#endif
