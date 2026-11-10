#include "config.h"

#include <avr/io.h>
#include <avr/interrupt.h>
#include <util/atomic.h>
#include <util/delay.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lcd_compat.h"

// ============================================================================
// ATmega128A 자동급식기 - Microchip Studio / AVR-GCC 변환본
// - CodeVisionAVR 원본 기능 유지
// - 기존 Android 앱 단일문자 명령 및 wx...y...z... 패킷 유지
// - Stage 5: 휴대폰 시간 동기화 + 매일 반복 예약 추가
// ============================================================================

#define U08 uint8_t
#define U32 uint32_t

#define SENSOR_WATER 0
#define SENSOR_FOOD  1

// 발표용 임시 우회: 물통 감지 케이블 고장 시 상태 패킷만 정상으로 고정한다.
// 기본 브랜치에는 적용하지 않으며, 하드웨어 수리 후 이 플래그를 0으로 되돌린다.
#define PRESENTATION_WATER_SENSOR_BYPASS 1

// [물통 HX711] PB0: DT, PB1: SCK
#define WATER_DIN      (PINB & _BV(PB0))
#define WATER_SCK_1()  (PORTB |= _BV(PB1))
#define WATER_SCK_0()  (PORTB &= (uint8_t)~_BV(PB1))

// [사료 HX711] PB2: DT, PB3: SCK
#define FOOD_DIN       (PINB & _BV(PB2))
#define FOOD_SCK_1()   (PORTB |= _BV(PB3))
#define FOOD_SCK_0()   (PORTB &= (uint8_t)~_BV(PB3))

// LED (Active Low)
#define LED_PILL_A_ON()   (PORTD &= (uint8_t)~_BV(PD0))
#define LED_PILL_A_OFF()  (PORTD |= _BV(PD0))
#define LED_PILL_B_ON()   (PORTD &= (uint8_t)~_BV(PD1))
#define LED_PILL_B_OFF()  (PORTD |= _BV(PD1))
#define LED_WATER_ON()    (PORTD &= (uint8_t)~_BV(PD4))
#define LED_WATER_OFF()   (PORTD |= _BV(PD4))
#define LED_FOOD_ON()     (PORTD &= (uint8_t)~_BV(PD6))
#define LED_FOOD_OFF()    (PORTD |= _BV(PD6))

// 모터
#define WATER_PUMP_ON()   (PORTE |= _BV(PE2))
#define WATER_PUMP_OFF()  (PORTE &= (uint8_t)~_BV(PE2))

static inline void food_motor_on(void)
{
    // 정방향: 사료 배출
    PORTE &= (uint8_t)~_BV(PE4);
    PORTE |= _BV(PE5);
}

static inline void food_motor_reverse(void)
{
    // 역방향: 사료가 스크류/배출구에 끼었을 때 잠깐 풀어준다.
    PORTE |= _BV(PE4);
    PORTE &= (uint8_t)~_BV(PE5);
}

static inline void food_motor_off(void)
{
    PORTE &= (uint8_t)~(_BV(PE4) | _BV(PE5));
}

static void food_motor_unjam(void)
{
    // 방향을 즉시 바꾸지 않고 잠깐 멈춘 뒤 역회전한다.
    food_motor_off();
    _delay_ms(FOOD_JAM_STOP_BEFORE_REVERSE_MS);

    food_motor_reverse();
    _delay_ms(FOOD_JAM_REVERSE_MS);

    food_motor_off();
    _delay_ms(FOOD_JAM_STOP_AFTER_REVERSE_MS);
}

// 자동 급식과 수동 모터 ON이 같은 무게 기반 끼임 감지/복구를 사용한다.
// 모터 소리나 실제 회전 속도를 측정하는 방식이 아니다.
typedef struct {
    int progress_weight;
    uint8_t stagnant_checks;
    uint8_t recovery_count;
} FoodJamMonitor;

#define FOOD_JAM_OK         0U
#define FOOD_JAM_RECOVERED  1U
#define FOOD_JAM_GIVE_UP    2U

static void food_jam_reset(FoodJamMonitor *monitor, int weight)
{
    monitor->progress_weight = weight;
    monitor->stagnant_checks = 0;
    monitor->recovery_count = 0;
}

static void food_jam_rebaseline(FoodJamMonitor *monitor, int weight)
{
    monitor->progress_weight = weight;
    monitor->stagnant_checks = 0;
}

// 반드시 새 로드셀 측정값이 들어온 뒤, 약 500ms마다 호출한다.
static uint8_t food_jam_check(FoodJamMonitor *monitor, int weight)
{
    if (weight >= monitor->progress_weight + FOOD_JAM_MIN_PROGRESS_G) {
        food_jam_rebaseline(monitor, weight);
        return FOOD_JAM_OK;
    }

    if (++monitor->stagnant_checks < FOOD_JAM_STAGNANT_CHECKS) {
        return FOOD_JAM_OK;
    }

    if (monitor->recovery_count >= FOOD_JAM_MAX_RECOVERY) {
        food_motor_off();
        LED_FOOD_ON();
        return FOOD_JAM_GIVE_UP;
    }

    // 이전 GitHub 코드와 동일: 정지 -> 역회전 -> 정지.
    food_motor_unjam();
    monitor->recovery_count++;
    monitor->stagnant_checks = 0;
    return FOOD_JAM_RECOVERED;
}

static FoodJamMonitor manual_food_jam;
static uint8_t manual_food_running = 0;
static uint32_t manual_food_last_check_ms = 0;
static volatile uint32_t system_uptime_ms = 0;

static uint32_t get_uptime_ms(void)
{
    uint32_t now;
    ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
        now = system_uptime_ms;
    }
    return now;
}

static void manual_food_stop(void)
{
    manual_food_running = 0;
    food_motor_off();
}

// ===== M3 덮개: 하드웨어 직렬 리미트 스위치 방식 =====
// 회로도상 스위치는 모터 전류 경로에 직렬 연결되며 PF1/PF2 입력에 연결되지 않음.
// 따라서 MCU는 끝 접촉을 직접 알 수 없고, 모터 드라이버 방향 출력만 제어함.
// 정상 종단 차단은 스위치+역방향 바이패스 다이오드가 담당해야 함.
// 실제 회로가 두 방향 각각 정지/역방향 복귀 가능한지 사전 검증 필수.
#define LID_DIR_STOP  0U
#define LID_DIR_OPEN  1U
#define LID_DIR_CLOSE 2U

volatile uint8_t lid_motion_dir = LID_DIR_STOP;
volatile uint16_t lid_travel_ms = 0;
volatile uint8_t lid_timeout_fault = 0;

static inline void lid_stop(void)
{
	ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
		PORTE &= (uint8_t)~(_BV(PE6) | _BV(PE7));
		lid_motion_dir = LID_DIR_STOP;
	}
}

static inline void lid_open(void)
{
	ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
		lid_timeout_fault = 0;
		lid_travel_ms = 0;
		PORTE &= (uint8_t)~_BV(PE6);
		PORTE |= _BV(PE7);
		lid_motion_dir = LID_DIR_OPEN;
	}
}

static inline void lid_close(void)
{
	ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
		lid_timeout_fault = 0;
		lid_travel_ms = 0;
		PORTE |= _BV(PE6);
		PORTE &= (uint8_t)~_BV(PE7);
		lid_motion_dir = LID_DIR_CLOSE;
	}
}

// ADC0
#define FIRST_ADC_INPUT 0
#define LAST_ADC_INPUT  0
#define ADC_VREF_TYPE   0x00
volatile uint16_t adc_data[LAST_ADC_INPUT - FIRST_ADC_INPUT + 1];

// 기존 센서/설정 변수
U32 water_offset = 0;
U32 food_offset = 0;
float waterF_weight = 0.0f, foodF_weight = 0.0f;
int water_weight = 0, food_weight = 0;
int dist_val = 0;
int start_mode = 0; // 0: 수동, 1: 자동
int water_w_set = DEFAULT_WATER_TARGET_G;
int food_w_set = DEFAULT_FOOD_TARGET_G;
int pill_a_count = PILL_CAPACITY;
int pill_b_count = PILL_CAPACITY;
int water_eat = 0;
int water_h_val = 0;
int food_h_val = 0;
int food_h_val_set = DEFAULT_FOOD_LEVEL_THRESHOLD;
int water_h_in = 0;

char tx_str[32];
char lcd_text[35];

// PN532 상태
volatile uint8_t nfc_mode = 0;
volatile uint8_t match_state = 0;
volatile uint8_t skip_cnt = 0;

// NFC 태그 2개: 0xA7 = 열기 / 0x99 = 닫기
// 종단 스위치는 하드웨어에서 전류 차단. 같은 태그 연속 재인식은 중복 구동하지 않음.
// 태그를 제거했다가 같은 태그를 다시 찍어도 반대 태그를 찍기 전엔 재구동하지 않음.
volatile uint8_t nfc_last_tag = 0xFF;

// 기존 단일 문자 명령
volatile char rx_cmd = 0;

// 줄 단위 패킷 수신
// w...\n : 기존 급식 패킷
// T...\n/R\n/S...\n/E\n/X\n : 생활모드 동기화 패킷
#define RX_LINE_SIZE 40
char rx_line[RX_LINE_SIZE];
volatile uint8_t rx_line_idx = 0;
volatile uint8_t rx_line_mode = 0;
volatile uint8_t rx_line_ready = 0;

// 28BYJ-48 4상 8박자
const U08 step_seq[8] = {0x01, 0x03, 0x02, 0x06, 0x04, 0x0C, 0x08, 0x09};

// ============================================================================
// Stage 5 - 생활 모드 시간/예약
// ============================================================================
typedef struct {
	uint8_t hour;
	uint8_t minute;
	uint16_t food_g;
	uint8_t pill_a;
	uint8_t pill_b;
} DailySchedule;

DailySchedule daily_schedules[MAX_DAILY_SCHEDULES];
uint8_t daily_schedule_count = 0;
uint8_t daily_schedule_enabled = 0;
uint8_t clock_synced = 0;
volatile U32 seconds_of_day = 0;
uint16_t last_checked_minute = 0xFFFF;

// 최근 생활 급식 실행 기록. Bluetooth가 끊겨 있어도 RAM에 보관했다가 Q 명령으로 재전송한다.
#define FEED_EVENT_CAPACITY 10
typedef struct {
	uint16_t sequence;
	uint8_t hour;
	uint8_t minute;
	uint16_t food_g;
	uint8_t pill_a;
	uint8_t pill_b;
} FeedEvent;

FeedEvent feed_events[FEED_EVENT_CAPACITY];
uint8_t feed_event_count = 0;
uint8_t feed_event_head = 0;
uint16_t next_feed_event_sequence = 1;

static U32 clock_get_seconds(void)
{
	U32 value;
	ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
		value = seconds_of_day;
	}
	return value;
}

static void clock_set_hms(uint8_t hour, uint8_t minute, uint8_t second)
{
	U32 value = ((U32)hour * 3600UL) + ((U32)minute * 60UL) + second;
	ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
		seconds_of_day = value % 86400UL;
	}
	clock_synced = 1;
	// 동기화 순간 같은 분의 예약이 즉시 중복 실행되지 않도록 현재 분을 소비 처리
	last_checked_minute = (uint16_t)(value / 60UL);
}

static void schedules_clear(void)
{
	daily_schedule_count = 0;
	daily_schedule_enabled = 0;
}

static uint8_t schedule_add(uint8_t hour, uint8_t minute, uint16_t food_g,
uint8_t pill_a, uint8_t pill_b)
{
	DailySchedule *s;

	if (daily_schedule_count >= MAX_DAILY_SCHEDULES) return 0;
	if (hour >= 24 || minute >= 60) return 0;
	if (food_g > 999) return 0;
	if (pill_a > PILL_CAPACITY || pill_b > PILL_CAPACITY) return 0;

	s = &daily_schedules[daily_schedule_count++];
	s->hour = hour;
	s->minute = minute;
	s->food_g = food_g;
	s->pill_a = pill_a;
	s->pill_b = pill_b;
	return 1;
}

// ============================================================================
// ADC ISR
// ============================================================================
ISR(ADC_vect)
{
	adc_data[0] = ADCW;
	_delay_us(10);
	ADCSRA |= _BV(ADSC);
}

// 1초 시스템 시계: Timer1 CTC, 16 MHz / 1024 / 15625 = 1 Hz
ISR(TIMER1_COMPA_vect)
{
	seconds_of_day++;
	if (seconds_of_day >= 86400UL) seconds_of_day = 0;

}

// Timer2 CTC 1ms: MCU는 리미트 접점 신호를 읽지 않음.
// 모터 전류는 기계적 끝 스위치에서 하드웨어 차단되며,
// 소프트웨어는 최대 구동시간만 제한한다. (정상 종단 상태 확인 불가)
ISR(TIMER2_COMP_vect)
{
    // 기존 1ms 타이머를 수동 급식 끼임 감지 주기에도 재사용한다.
    system_uptime_ms++;
	if (lid_motion_dir == LID_DIR_STOP) return;
	// 고장/오배선 시 최후 타임아웃. 구동 시간이 아닌 끝 검출로 사용 금지.
	if (lid_travel_ms < LID_EMERGENCY_TIMEOUT_MS) lid_travel_ms++;
	if (lid_travel_ms >= LID_EMERGENCY_TIMEOUT_MS) {
		lid_timeout_fault = 1;
		lid_stop();
	}
}

// ============================================================================
// PN532
// ============================================================================
const uint8_t PN532_WAKEUP[] = {
	0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0xFF, 0x03, 0xFD, 0xD4, 0x14, 0x01, 0x17, 0x00
};

const uint8_t PN532_INLIST[] = {
	0x00, 0x00, 0xFF, 0x04, 0xFC, 0xD4, 0x4A, 0x01, 0x00, 0xE1, 0x00
};

// ============================================================================
// UART
// ============================================================================
static void TX0_char(char data)
{
	while (!(UCSR0A & _BV(UDRE0))) { }
	UDR0 = data;
}

static void TX0_STR(const char *s)
{
	while (*s) TX0_char(*s++);
}

static void send_feed_event(const FeedEvent *event)
{
	snprintf(tx_str, sizeof(tx_str), "!L,%u,%02u%02u,%u,%u,%u\n",
	(unsigned int)event->sequence,
	(unsigned int)event->hour,
	(unsigned int)event->minute,
	(unsigned int)event->food_g,
	(unsigned int)event->pill_a,
	(unsigned int)event->pill_b);
	TX0_STR(tx_str);
}

static void record_life_feed_event(uint8_t hour, uint8_t minute,
uint16_t food_g, uint8_t pill_a, uint8_t pill_b)
{
	uint8_t index;
	FeedEvent *event;

	if (feed_event_count < FEED_EVENT_CAPACITY) {
		index = (uint8_t)((feed_event_head + feed_event_count) % FEED_EVENT_CAPACITY);
		feed_event_count++;
		} else {
		index = feed_event_head;
		feed_event_head = (uint8_t)((feed_event_head + 1) % FEED_EVENT_CAPACITY);
	}

	event = &feed_events[index];
	event->sequence = next_feed_event_sequence++;
	if (next_feed_event_sequence == 0) next_feed_event_sequence = 1;
	event->hour = hour;
	event->minute = minute;
	event->food_g = food_g;
	event->pill_a = pill_a;
	event->pill_b = pill_b;

	// 연결되어 있으면 즉시 앱이 받으며, 연결이 없더라도 UART 송신 자체는 장치 동작에 영향을 주지 않는다.
	send_feed_event(event);
}

static void replay_feed_events(void)
{
	uint8_t i;
	for (i = 0; i < feed_event_count; i++) {
		uint8_t index = (uint8_t)((feed_event_head + i) % FEED_EVENT_CAPACITY);
		send_feed_event(&feed_events[index]);
	}
}

ISR(USART0_RX_vect)
{
	char data = UDR0;

	if (rx_line_mode) {
		if (data == '\n' || data == '\r') {
			if (rx_line_idx > 0 && !rx_line_ready) {
				rx_line[rx_line_idx] = '\0';
				rx_line_ready = 1;
			}
			rx_line_idx = 0;
			rx_line_mode = 0;
			} else if (rx_line_idx < (RX_LINE_SIZE - 1)) {
			rx_line[rx_line_idx++] = data;
			} else {
			// 비정상적으로 긴 패킷은 버림
			rx_line_idx = 0;
			rx_line_mode = 0;
		}
		return;
	}

	// 다문자 패킷의 시작 문자. 기존 단일문자는 모두 소문자라 충돌하지 않는다.
	if (data == 'w' || data == 'T' || data == 'R' || data == 'S' ||
	data == 'E' || data == 'X' || data == 'Q') {
		rx_line_mode = 1;
		rx_line_idx = 0;
		rx_line[rx_line_idx++] = data;
		return;
	}

	// 기존 앱 개발자/제어 명령
	rx_cmd = data;
}

static void uart1_transmit(uint8_t data)
{
	while (!(UCSR1A & _BV(UDRE1))) { }
	UDR1 = data;
}

static void send_pn532_cmd(const uint8_t *cmd, uint8_t len)
{
	uint8_t i;
	for (i = 0; i < len; i++) uart1_transmit(cmd[i]);
}

ISR(USART1_RX_vect)
{
	uint8_t dataNFC = UDR1;

	switch (match_state) {
		case 0:
		if (dataNFC == 0x0C) match_state = 1;
		break;
		case 1:
		if (dataNFC == 0xF4) {
			match_state = 2;
			skip_cnt = 8;
			} else if (dataNFC != 0x0C) {
			match_state = 0;
		}
		break;
		case 2:
		if (skip_cnt > 0) skip_cnt--;
		if (skip_cnt == 0) match_state = 3;
		break;
		case 3:
		// NFC 인식 후 main()의 센서 처리를 기다리지 않고
		// UART1 수신 ISR에서 모터 제어 핀을 바로 변경한다.
		// 출력 전환은 짧은 레지스터 조작이므로 ISR에서 처리 가능.
		if (dataNFC == 0xA7) {
			nfc_mode = 0;
			if (nfc_last_tag != 0xA7) {
				nfc_last_tag = 0xA7;
				lid_open();
			}
			} else if (dataNFC == 0x99) {
			nfc_mode = 1;
			if (nfc_last_tag != 0x99) {
				nfc_last_tag = 0x99;
				lid_close();
			}
		}
		match_state = 0;
		break;
		default:
		match_state = 0;
		break;
	}
}

// ============================================================================
// HX711
// ============================================================================
static U32 getValue(U08 sensor)
{
	U08 i;
	U32 data = 0;

	if (sensor == SENSOR_WATER) {
		while (WATER_DIN) { }
		} else {
		while (FOOD_DIN) { }
	}

	for (i = 0; i < 24; i++) {
		if (sensor == SENSOR_WATER) {
			WATER_SCK_1();
			_delay_us(1);
			if (WATER_DIN) data |= (0x800000UL >> i);
			WATER_SCK_0();
			_delay_us(1);
			} else {
			FOOD_SCK_1();
			_delay_us(1);
			if (FOOD_DIN) data |= (0x800000UL >> i);
			FOOD_SCK_0();
			_delay_us(1);
		}
	}

	if (sensor == SENSOR_WATER) {
		WATER_SCK_1(); _delay_us(1); WATER_SCK_0();
		} else {
		FOOD_SCK_1(); _delay_us(1); FOOD_SCK_0();
	}

	data ^= 0x800000UL;
	return data;
}

static void setOffset(U08 sensor)
{
	if (sensor == SENSOR_WATER) water_offset = getValue(SENSOR_WATER);
	else food_offset = getValue(SENSOR_FOOD);
}

static float getGram(U08 sensor, U08 sample_count)
{
	int32_t val;
	U32 sum = 0;
	U08 i;

	for (i = 0; i < sample_count; i++) sum += getValue(sensor);
	sum /= sample_count;

	if (sensor == SENSOR_WATER) {
		val = (int32_t)(sum - water_offset);
		return (float)val / WATER_SCALE;
	}

	val = (int32_t)(sum - food_offset);
	return (float)val / FOOD_SCALE;
}

static void HX711init(void)
{
	DDRB |= _BV(PB1) | _BV(PB3);
	DDRB &= (uint8_t)~(_BV(PB0) | _BV(PB2));

	WATER_SCK_1();
	FOOD_SCK_1();
	_delay_us(100);
	WATER_SCK_0();
	FOOD_SCK_0();

	// 원본과 동일하게 각 센서 오프셋을 2회 읽고 마지막 값을 사용
	setOffset(SENSOR_WATER);
	setOffset(SENSOR_WATER);
	setOffset(SENSOR_FOOD);
	setOffset(SENSOR_FOOD);
}

// ============================================================================
// 센서
// ============================================================================
static void read_all_sensors(void)
{
	int32_t adc_sum = 0;
	uint8_t i;

	// Water_H_sensor (PD5)
	water_h_val = (PIND & _BV(PD5)) ? 1 : 0;

	// Food_H_sensor (ADC0) 10회 평균
	for (i = 0; i < 10; i++) {
		uint16_t sample;
		ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
			sample = adc_data[0];
		}
		adc_sum += sample;
		_delay_us(100);
	}
	food_h_val = (int)(adc_sum / 10);

	// 무게 측정
	waterF_weight = getGram(SENSOR_WATER, 2);
	water_weight = (int)(waterF_weight * -1.0f);
	if (water_weight < 0) {
		water_weight = 0;
		water_h_val = 0;
		WATER_PUMP_OFF();
		LED_WATER_ON();
	}

#if PRESENTATION_WATER_SENSOR_BYPASS
	// PD5 물통 감지 입력을 사용하지 않고 앱/상태 패킷에는 항상 정상으로 보고한다.
	water_h_val = 1;
#endif

	foodF_weight = getGram(SENSOR_FOOD, 2);
	food_weight = (int)(foodF_weight * -1.0f);
	if (food_weight < 0) food_weight = 0;

	lcd_gotoxy(0, 0);
	snprintf(lcd_text, sizeof(lcd_text), "M%d Ww%3d S%3d H%d",
	start_mode, water_weight, water_w_set, water_h_val);
	lcd_puts(lcd_text);

	lcd_gotoxy(0, 1);
	snprintf(lcd_text, sizeof(lcd_text), "N%d F%3d H%3dA%dB%d",
	nfc_mode, food_weight, food_h_val, pill_a_count, pill_b_count);
	lcd_puts(lcd_text);
}

// 수동 모터 ON도 자동 급식과 동일하게 500ms 간격/2g 기준으로 확인한다.
// 별도 센서는 사용하지 않고, main()에서 이미 갱신한 사료 로드셀값을 사용한다.
static void manual_food_jam_update(void)
{
    uint32_t now;
    uint8_t result;

    if (!manual_food_running) return;

    // 덮개 닫힘 명령이 들어오면 재정방향 구동하지 않는다.
    if (nfc_mode == 1) {
        manual_food_stop();
        return;
    }

    now = get_uptime_ms();
    if ((uint32_t)(now - manual_food_last_check_ms) <
        (uint32_t)FOOD_JAM_CHECK_INTERVAL_MS) return;
    manual_food_last_check_ms = now;

    result = food_jam_check(&manual_food_jam, food_weight);
    if (result == FOOD_JAM_GIVE_UP) {
        manual_food_stop();
        return;
    }
    if (result == FOOD_JAM_RECOVERED) {
        // 역회전 중 사료가 풀렸다면 새 기준값으로 재시도한다.
        read_all_sensors();
        food_jam_rebaseline(&manual_food_jam, food_weight);
        manual_food_last_check_ms = get_uptime_ms();
        if (manual_food_running && nfc_mode != 1) {
            food_motor_on();
        } else {
            manual_food_stop();
        }
    }
}

// ============================================================================
// 스텝모터
// ============================================================================
static void step_motor1_45deg(uint8_t dir)
{
	uint16_t i;
	for (i = 0; i < 512; i++) {
		U08 idx = (dir == 1) ? (i % 8) : (7 - (i % 8));
		PORTA = (PORTA & 0xF0) | step_seq[idx];
		_delay_ms(2);
	}
}

static void step_motor2_45deg(uint8_t dir)
{
	uint16_t i;
	for (i = 0; i < 512; i++) {
		U08 idx = (dir == 1) ? (i % 8) : (7 - (i % 8));
		PORTA = (PORTA & 0x0F) | (uint8_t)(step_seq[idx] << 4);
		_delay_ms(2);
	}
}

// ============================================================================
// 급식 공통 실행
// ============================================================================
static void execute_feeding(uint16_t food_target_g, uint8_t pill_a, uint8_t pill_b)
{
	uint8_t k;

	food_w_set = (int)food_target_g;
    // 자동 급식이 시작되면 수동 회전을 종료하고 제어권을 넘긴다.
    manual_food_stop();

	// 안전: 본 회로에서는 MCU에 실제 덮개 열림 완료 피드백이 없다.
	// 닫힘 상태에서 자동 급식 요청이 오면 급식을 진행하지 않는다.
	// NFC로 덮개를 열고 실제 열림 여부를 확인한 후 재요청해야 한다.
	if (nfc_mode == 1) return;

    // 사료 배출: 자동/예약 급식도 수동 모터 ON과 같은 끼임 복구 함수를 사용한다.
    if (food_target_g > 0) {
        FoodJamMonitor auto_food_jam;

        read_all_sensors();
        food_jam_reset(&auto_food_jam, food_weight);

        while (1) {
            uint8_t result;

            if (nfc_mode == 1 || food_h_val < food_h_val_set) {
                food_motor_off();
                LED_FOOD_ON();
                break;
            }

            LED_FOOD_OFF();
            if (food_weight >= food_w_set) {
                food_motor_off();
                break;
            }

            food_motor_on();
            _delay_ms(FOOD_JAM_CHECK_INTERVAL_MS);
            read_all_sensors();

            if (food_weight >= food_w_set) {
                food_motor_off();
                break;
            }

            result = food_jam_check(&auto_food_jam, food_weight);
            if (result == FOOD_JAM_GIVE_UP) {
                break;
            }
            if (result == FOOD_JAM_RECOVERED) {
                read_all_sensors();
                food_jam_rebaseline(&auto_food_jam, food_weight);
                // 다음 반복에서 덮개/사료량/목표량 검사 후 정방향으로 재시작.
            }
        }
    }

	// 약 A
	for (k = 0; k < pill_a; k++) {
		step_motor1_45deg(0);
		if (pill_a_count > 0) pill_a_count--;
		if (pill_a_count == 0) LED_PILL_A_ON();
		else LED_PILL_A_OFF();
		read_all_sensors();
	}

	// 약 B
	for (k = 0; k < pill_b; k++) {
		step_motor2_45deg(0);
		if (pill_b_count > 0) pill_b_count--;
		if (pill_b_count == 0) LED_PILL_B_ON();
		else LED_PILL_B_OFF();
		read_all_sensors();
	}
}

// 기존 wx200y1z2 형식
static void process_feeding_packet(char *buf)
{
	char *x_ptr = strchr(buf, 'x');
	char *y_ptr = strchr(buf, 'y');
	char *z_ptr = strchr(buf, 'z');
	int food = food_w_set;
	int pill_a = 0;
	int pill_b = 0;

	if (x_ptr != NULL) food = atoi(x_ptr + 1);
	if (y_ptr != NULL) pill_a = atoi(y_ptr + 1);
	if (z_ptr != NULL) pill_b = atoi(z_ptr + 1);

	if (food < 0) food = 0;
	if (food > 999) food = 999;
	if (pill_a < 0) pill_a = 0;
	if (pill_b < 0) pill_b = 0;
	if (pill_a > PILL_CAPACITY) pill_a = PILL_CAPACITY;
	if (pill_b > PILL_CAPACITY) pill_b = PILL_CAPACITY;

	if (start_mode == 1) {
		execute_feeding((uint16_t)food, (uint8_t)pill_a, (uint8_t)pill_b);
	}
}

// ============================================================================
// Stage 5 패킷 처리
// ============================================================================
static uint8_t parse_two_digits(const char *p, uint8_t *value)
{
	if (p[0] < '0' || p[0] > '9' || p[1] < '0' || p[1] > '9') return 0;
	*value = (uint8_t)((p[0] - '0') * 10 + (p[1] - '0'));
	return 1;
}

static void process_stage5_line(char *buf)
{
	if (buf[0] == 'w') {
		process_feeding_packet(buf);
		return;
	}

	// T083045 : 08:30:45로 장치 시간 동기화
	if (buf[0] == 'T') {
		uint8_t hh, mm, ss;
		if (strlen(buf) >= 7 &&
		parse_two_digits(&buf[1], &hh) &&
		parse_two_digits(&buf[3], &mm) &&
		parse_two_digits(&buf[5], &ss) &&
		hh < 24 && mm < 60 && ss < 60) {
			clock_set_hms(hh, mm, ss);
		}
		return;
	}

	// R : 기존 생활 예약 모두 삭제 후 동기화 시작
	if (buf[0] == 'R' && buf[1] == '\0') {
		schedules_clear();
		return;
	}

	// S0830x65y1z0 : 08:30 / 사료 65g / 약A 1 / 약B 0
	if (buf[0] == 'S') {
		uint8_t hh, mm;
		char *x_ptr;
		char *y_ptr;
		char *z_ptr;
		int food;
		int pill_a;
		int pill_b;

		if (strlen(buf) < 5 || !parse_two_digits(&buf[1], &hh) ||
		!parse_two_digits(&buf[3], &mm)) return;

		x_ptr = strchr(buf, 'x');
		y_ptr = strchr(buf, 'y');
		z_ptr = strchr(buf, 'z');
		if (!x_ptr || !y_ptr || !z_ptr) return;

		food = atoi(x_ptr + 1);
		pill_a = atoi(y_ptr + 1);
		pill_b = atoi(z_ptr + 1);

		if (food < 0 || food > 999 || pill_a < 0 || pill_b < 0) return;
		schedule_add(hh, mm, (uint16_t)food, (uint8_t)pill_a, (uint8_t)pill_b);
		return;
	}

	// E : 전송된 예약을 활성화하고 생활 자동모드 시작
	if (buf[0] == 'E' && buf[1] == '\0') {
		daily_schedule_enabled = 1;
		start_mode = 1;
		last_checked_minute = (uint16_t)(clock_get_seconds() / 60UL);
		return;
	}

	// X : 예약 실행만 일시 중지. 자동 급수 모드는 유지한다.
	if (buf[0] == 'X' && buf[1] == '\0') {
		daily_schedule_enabled = 0;
		return;
	}

	// Q : 현재 전원 세션 동안 보관된 생활 급식 실행 이벤트 재전송
	if (buf[0] == 'Q' && buf[1] == '\0') {
		replay_feed_events();
		return;
	}
}

static void check_daily_schedules(void)
{
	U32 now;
	uint16_t minute_of_day;
	uint8_t i;

	if (!clock_synced || !daily_schedule_enabled) return;

	now = clock_get_seconds();
	minute_of_day = (uint16_t)(now / 60UL);

	if (minute_of_day == last_checked_minute) return;
	last_checked_minute = minute_of_day;

	for (i = 0; i < daily_schedule_count; i++) {
		uint16_t schedule_minute = (uint16_t)daily_schedules[i].hour * 60U + daily_schedules[i].minute;
		if (schedule_minute == minute_of_day) {
			execute_feeding(daily_schedules[i].food_g,
			daily_schedules[i].pill_a,
			daily_schedules[i].pill_b);
			record_life_feed_event(daily_schedules[i].hour,
			daily_schedules[i].minute,
			daily_schedules[i].food_g,
			daily_schedules[i].pill_a,
			daily_schedules[i].pill_b);
		}
	}
}

// ============================================================================
// 기존 단일 문자 명령
// ============================================================================
static void process_single_command(char cmd)
{
	switch (cmd) {
		// 수동 사료 모터: 자동 급식과 같은 로드셀 끼임 복구 적용.
        case 'a':
            if (nfc_mode == 1) {
                manual_food_stop();
                break;
            }
            if (!manual_food_running) {
                food_jam_reset(&manual_food_jam, food_weight);
                manual_food_last_check_ms = get_uptime_ms();
                manual_food_running = 1;
            }
            LED_FOOD_OFF();
            food_motor_on();
            break;
        case 'b':
            manual_food_stop();
            break;
        // 직접 역회전 테스트는 자동 수동 감지와 구별하여 1회만 실행.
        case 't':
            manual_food_stop();
            food_motor_unjam();
            break;

		// 물 펌프
		case 'c': WATER_PUMP_ON(); break;
		case 'd': WATER_PUMP_OFF(); break;

		// 덮개
		case 'e': // 앱에서 직접 닫기
		nfc_last_tag = 0xFF;
		lid_close();
		nfc_mode = 1;
		break;
		case 'f': // 앱에서 직접 열기
		nfc_last_tag = 0xFF;
		lid_open();
		nfc_mode = 0;
		break;
		case 'g': // 앱에서 덮개 정지
		lid_stop();
		break;

		// 약통 스텝모터 45도
		case 'h': step_motor1_45deg(0); break;
		case 'i': step_motor2_45deg(0); break;

		// 전체 설정 초기화
		case 'j':
        manual_food_stop();
		start_mode = 0;
		pill_a_count = PILL_CAPACITY;
		pill_b_count = PILL_CAPACITY;
		nfc_mode = 0;
		nfc_last_tag = 0xFF;
		lid_stop();
		PORTD |= 0x53;
		water_w_set = DEFAULT_WATER_TARGET_G;
		food_h_val_set = DEFAULT_FOOD_LEVEL_THRESHOLD;
		water_eat = 0;
		daily_schedule_enabled = 0;
		break;

		// 자동/수동 토글
		case 'k':
		start_mode = (start_mode == 0) ? 1 : 0;
		break;

		// 보충 완료
		case 'm':
		PORTD |= 0x53;
		pill_a_count = PILL_CAPACITY;
		pill_b_count = PILL_CAPACITY;
		break;

		// 물 유지량 +/- 10g
		case 'o': water_w_set += 10; break;
		case 'p': water_w_set -= 10; break;

		default:
		break;
	}
}

// ============================================================================
// 기존 18자리 D 패킷 송신
// ============================================================================
static void send_status_packet(void)
{
	TX0_char('D');

	snprintf(tx_str, sizeof(tx_str), "%d", start_mode);
	TX0_STR(tx_str);

	if (water_w_set >= 100) snprintf(tx_str, sizeof(tx_str), "%3d", water_w_set);
	else if (water_w_set >= 10) snprintf(tx_str, sizeof(tx_str), "0%2d", water_w_set);
	else snprintf(tx_str, sizeof(tx_str), "00%d", water_w_set);
	TX0_STR(tx_str);

	if (water_weight >= 100) snprintf(tx_str, sizeof(tx_str), "%3d", water_weight);
	else if (water_weight >= 10) snprintf(tx_str, sizeof(tx_str), "0%2d", water_weight);
	else snprintf(tx_str, sizeof(tx_str), "00%d", water_weight);
	TX0_STR(tx_str);

	if (food_weight >= 100) snprintf(tx_str, sizeof(tx_str), "%3d", food_weight);
	else if (food_weight >= 10) snprintf(tx_str, sizeof(tx_str), "0%2d", food_weight);
	else snprintf(tx_str, sizeof(tx_str), "00%d", food_weight);
	TX0_STR(tx_str);

	snprintf(tx_str, sizeof(tx_str), "%d", pill_a_count); TX0_STR(tx_str);
	snprintf(tx_str, sizeof(tx_str), "%d", pill_b_count); TX0_STR(tx_str);
	snprintf(tx_str, sizeof(tx_str), "%d", water_h_val); TX0_STR(tx_str);
	snprintf(tx_str, sizeof(tx_str), "%d", nfc_mode); TX0_STR(tx_str);

	TX0_char((food_h_val < food_h_val_set) ? '0' : '1');

	if (water_eat >= 10) snprintf(tx_str, sizeof(tx_str), "%2d", water_eat);
	else snprintf(tx_str, sizeof(tx_str), "0%d", water_eat);
	TX0_STR(tx_str);
}

// ============================================================================
// 초기화
// ============================================================================
static void timer1_clock_init(void)
{
	TCCR1A = 0x00;
	TCCR1B = 0x00;
	TCNT1 = 0;
	OCR1A = 15624;
	TCCR1B = _BV(WGM12) | _BV(CS12) | _BV(CS10); // CTC, /1024
	TIMSK |= _BV(OCIE1A);
}

static void timer2_endstop_init(void)
{
	// 16 MHz / 64 / (249+1) = 1000 Hz = 1 ms
	TCNT2 = 0;
	OCR2 = 249;
	TCCR2 = _BV(WGM21) | _BV(CS22);
	TIMSK |= _BV(OCIE2);
}

static void timer3_pwm_init(void)
{
	// 원본: TCCR3A=0x81, TCCR3B=0x0B, OCR3A=120
	// OC3A(PE3), Fast PWM 8-bit, non-inverting, /64
	TCCR3A = _BV(COM3A1) | _BV(WGM30);
	TCCR3B = _BV(WGM32) | _BV(CS31) | _BV(CS30);
	OCR3A = 120;
}

static void adc_init(void)
{
	ADMUX = FIRST_ADC_INPUT | (ADC_VREF_TYPE & 0xFF);
	// ADC enable + start conversion + interrupt enable + prescaler /16
	ADCSRA = _BV(ADEN) | _BV(ADSC) | _BV(ADIE) | _BV(ADPS2);
}

static void uart_init(void)
{
	// UART0 Bluetooth: 9600 bps @ 16 MHz
	UCSR0A = 0x00;
	UBRR0H = 0x00;
	UBRR0L = 103;
	UCSR0C = _BV(UCSZ01) | _BV(UCSZ00);
	UCSR0B = _BV(RXCIE0) | _BV(RXEN0) | _BV(TXEN0);

	// UART1 PN532: 약 115200 bps @ 16 MHz, 원본 UBRR=8 유지
	UCSR1A = 0x00;
	UBRR1H = 0x00;
	UBRR1L = 8;
	UCSR1C = _BV(UCSZ11) | _BV(UCSZ10);
	UCSR1B = _BV(RXCIE1) | _BV(RXEN1) | _BV(TXEN1);
}

int main(void)
{
	DDRA = 0xFF;
	PORTA = 0x00;

	// LED PD0, PD1, PD4, PD6 출력 / Water_H PD5 입력
	DDRD |= 0x53;
	DDRD &= (uint8_t)~_BV(PD5);
	PORTD |= 0x53;

	// PE2~PE7 모터 출력
	DDRE |= 0xFC;
	PORTE &= (uint8_t)~0xFC;

	timer3_pwm_init();
	timer1_clock_init();
	timer2_endstop_init();
	adc_init();
	uart_init();

	HX711init();

	lcd_init(16);
	lcd_clear();
	lcd_gotoxy(0, 0);
	lcd_puts("NFC V8 BOTH JAM");
	_delay_ms(1200);
	lcd_clear();

	_delay_ms(100);
	send_pn532_cmd(PN532_WAKEUP, sizeof(PN532_WAKEUP));
	_delay_ms(300);

	sei();

	while (1) {
		// 1. NFC 탐색
		send_pn532_cmd(PN532_INLIST, sizeof(PN532_INLIST));

		// 2. 센서 갱신
		read_all_sensors();

		// 3. NFC는 USART1 수신 인터럽트에서 덮개 방향을 결정한다.
		//    실제 끝 위치 정지는 모터 회로의 직렬 스위치가 수행한다.

		// 4. 자동 급수 제어 (기존 기능 유지)
		if (start_mode == 1) {
			if (water_weight < 0) {
				water_weight = 0;
				water_h_val = 0;
				WATER_PUMP_OFF();
				LED_WATER_ON();
			}

			if (water_h_val == 0) {
				WATER_PUMP_OFF();
				LED_WATER_ON();
				} else {
				LED_WATER_OFF();
				if (water_h_in == 0) {
					if (water_weight < water_w_set) {
						WATER_PUMP_ON();
						} else {
						WATER_PUMP_OFF();
						water_h_in = 1;
					}
					} else if (water_weight < water_w_set - 30) {
					water_h_in = 0;
					water_eat++;
				}
			}

		}

		// 5. 줄 단위 명령 처리
		if (rx_line_ready) {
			char local_line[RX_LINE_SIZE];
			ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
				strncpy(local_line, rx_line, RX_LINE_SIZE);
				local_line[RX_LINE_SIZE - 1] = '\0';
				rx_line_ready = 0;
			}
			process_stage5_line(local_line);
		}

		// 5. 기존 단일문자 명령
		if (rx_cmd != 0) {
			char cmd;
			ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
				cmd = rx_cmd;
				rx_cmd = 0;
			}
			process_single_command(cmd);
		}

        // 6. 앱 개발자 모드의 'a' 수동 구동도 끼임을 지속 감시한다.
        manual_food_jam_update();

		// 7. 휴대폰 연결 없이도 실행되는 생활모드 예약 확인
		check_daily_schedules();

		// 7. 기존 앱과 호환되는 상태 패킷
		send_status_packet();
		_delay_ms(150);
	}
}
