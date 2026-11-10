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

// === M3 덮개 종단 스위치 배선 ===
// 사진의 1번 = 열림 끝: PF1 / 2번 = 닫힘 끝: PF2 (실제 방향 확인 후 교환)
// 기본 배선: 스위치 COM -> GND, NC -> 해당 PF 핀
// NC: 안 눌림=LOW, 눌림/단선=HIGH(내부 풀업), 고장 시 구동 차단
// NO 단자 사용 시 LID_ENDSTOP_NC_CONTACT를 0으로 변경 (단선 보호 약함)
// 포트 F의 PF0(ADC0)는 기존 사료 센서 사용 중, PF1/PF2는 프로젝트 소스상 미사용.
// 이 설정은 실제 하드웨어 배선이 이 구성일 때만 정상 동작한다.
#define LID_OPEN_LIMIT_PIN        1
#define LID_CLOSE_LIMIT_PIN       2
#define LID_ENDSTOP_NC_CONTACT   1

// 일반 운전은 리미트 스위치에서 정지. 스위치 불량/단선 시 30초 안전 차단.
// 30초는 상한 보호 장치이며, 예전 5초 구동 시간이 아니다.
#define LID_EMERGENCY_TIMEOUT_MS  30000U

#endif
