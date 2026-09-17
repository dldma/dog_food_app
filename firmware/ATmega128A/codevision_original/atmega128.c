//=====================================================================//
// 코드비젼 컴파일러 - ATmega128
//=====================================================================//
#include <mega128.h>
#include <delay.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

//=====================================================================//
#define UDRE 5
#define RXC  7

#define U08 unsigned char
#define U32 unsigned long

// [물통] PB0: DT(DIN), PB1: SCK
#define WATER_DIN      (PINB & 0x01)       // PINB0
#define WATER_SCK_1    PORTB |= 0x02       // PORTB1 HIGH
#define WATER_SCK_0    PORTB &= ~0x02      // PORTB1 LOW

// [사료통] PB2: DT(DIN), PB3: SCK
#define FOOD_DIN       (PINB & 0x04)       // PINB2
#define FOOD_SCK_1     PORTB |= 0x08       // PORTB3 HIGH
#define FOOD_SCK_0     PORTB &= ~0x08      // PORTB3 LOW
//=====================================================================//
//ADC 체널 0번 사용 설정
#define FIRST_ADC_INPUT 0
#define LAST_ADC_INPUT 0
unsigned int adc_data[LAST_ADC_INPUT-FIRST_ADC_INPUT+1];
#define ADC_VREF_TYPE 0x00
//=====================================================================//

// 로드셀 구분 식별자
#define SENSOR_WATER   0
#define SENSOR_FOOD    1

// 변수 설정
U32 water_offset = 0;
U32 food_offset = 0;

float water_scale = 1038.0;
float food_scale = 1076.0;

float waterF_weight = 0.0, foodF_weight = 0.0;
int water_weight = 0, food_weight = 0;

int dist_val = 0;
int start_mode = 0; // 0: 수동모드, 1: 자동모드
int water_w_set = 100, food_w_set = 50;
int pill_a_count = 7, pill_b_count = 7;
int water_eat = 0;

// 센서 전역 변수
int water_h_val = 0;
int food_h_val = 0,food_h_val_set=290;
int water_h_in = 0; //0일때 100까지공급 1일때 -30일때 공급

U08 str[30];

//==================================================================
// Alphanumeric LCD Module functions
#asm
   .equ __lcd_port=0x15 ;PORTC
#endasm
#include <lcd.h>
//==================================================================
char lcd_text[35];

// 수신 버퍼 및 명령 처리 변수
volatile char rx_cmd = 0;         // 단일 명령 처리용
char rx_buffer[32];               // 패킷 수신 버퍼
volatile unsigned char rx_idx = 0;
volatile unsigned char packet_ready = 0;
volatile unsigned char is_packet_mode = 0;

// 제어 및 디스플레이 변수
volatile unsigned char nfc_mode = 0;
volatile unsigned char match_state = 0;
volatile unsigned char skip_cnt = 0;

//==================================================================
// 28BYJ-48 4상 8박자 스텝모터 시퀀스
const U08 step_seq[8] = {0x01, 0x03, 0x02, 0x06, 0x04, 0x0C, 0x08, 0x09};

//=====================================================================//
// ADC interrupt service routine
interrupt [ADC_INT] void adc_isr(void)
{
    static unsigned char input_index=0;
    adc_data[input_index]=ADCW;
    if (++input_index > (LAST_ADC_INPUT-FIRST_ADC_INPUT))
       input_index=0;
    ADMUX=(FIRST_ADC_INPUT | (ADC_VREF_TYPE & 0xff))+input_index;
    delay_us(10);
    ADCSRA|=0x40;
}

//==================================================================
// PN532 명령 패킷
const unsigned char PN532_WAKEUP[] = {
    0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0xFF, 0x03, 0xFD, 0xD4, 0x14, 0x01, 0x17, 0x00
};
const unsigned char PN532_INLIST[] = {
    0x00, 0x00, 0xFF, 0x04, 0xFC, 0xD4, 0x4A, 0x01, 0x00, 0xE1, 0x00
};

//=============================================================================
// UART 송수신 함수 및 인터럽트
//=============================================================================
void TX0_char(char TX0_data){ while ((UCSR0A&0x20)==0); UDR0=TX0_data; } // 송신함수
void TX0_STR(char *s){ while(*s)TX0_char(*s++); }
interrupt [USART0_RXC] void usart0_rx_isr(void)
{
    char data = UDR0;

    // 패킷 수신 모드 (w로 시작)
    if (data == 'w' || is_packet_mode) {
        is_packet_mode = 1;

        if (data == '\n' || data == '\r' || rx_idx >= 30) {
            rx_buffer[rx_idx] = '\0';
            packet_ready = 1;
            is_packet_mode = 0;
            rx_idx = 0;
        } else {
            rx_buffer[rx_idx++] = data;
        }
    }
    // 단일 문자 개별 명령 모드
    else {
        rx_cmd = data;
    }
}

void uart1_transmit(unsigned char dataNFC) {
    while (!(UCSR1A & (1<<UDRE)));
    UDR1 = dataNFC;
}

void send_pn532_cmd(const unsigned char *cmd, unsigned char len) {
    unsigned char i;
    for(i = 0; i < len; i++) uart1_transmit(cmd[i]);
}

interrupt [USART1_RXC] void usart1_rx_isr(void)
{
    unsigned char dataNFC = UDR1;

    switch(match_state) {
        case 0:
            if (dataNFC == 0x0C) match_state = 1;
            break;
        case 1:
            if (dataNFC == 0xF4) {
                match_state = 2;
                skip_cnt = 8;
            }
            else if (dataNFC != 0x0C) match_state = 0;
            break;
        case 2:
            skip_cnt--;
            if (skip_cnt == 0) {
                match_state = 3;
            }
            break;
        case 3:
            if (dataNFC == 0xA7) {
                nfc_mode = 0;
            }
            else if (dataNFC == 0x99) {
                nfc_mode = 1;
            }
            match_state = 0;
            break;
        default:
            match_state = 0;
            break;
    }
}

//=============================================================================
// HX711 함수
//=============================================================================
U32 getValue(U08 sensor) {
    U08 i;
    U32 data = 0;

    if (sensor == SENSOR_WATER) {
        while (WATER_DIN);
    } else {
        while (FOOD_DIN);
    }

    for (i = 0; i < 24; i++) {
        if (sensor == SENSOR_WATER) {
            WATER_SCK_1; delay_us(1);
            if (WATER_DIN) data |= (0x800000 >> i);
            WATER_SCK_0; delay_us(1);
        } else {
            FOOD_SCK_1; delay_us(1);
            if (FOOD_DIN) data |= (0x800000 >> i);
            FOOD_SCK_0; delay_us(1);
        }
    }

    if (sensor == SENSOR_WATER) {
        WATER_SCK_1; delay_us(1); WATER_SCK_0;
    } else {
        FOOD_SCK_1; delay_us(1); FOOD_SCK_0;
    }

    data ^= 0x800000;
    return data;
}

void setOffset(U08 sensor) {
    if (sensor == SENSOR_WATER) {
        water_offset = getValue(SENSOR_WATER);
    } else {
        food_offset = getValue(SENSOR_FOOD);
    }
}

float getGram(U08 sensor, U08 sum32) {
    long val;
    U32 sum = 0;
    U08 i;

    for (i = 0; i < sum32; i++) {
        sum += getValue(sensor);
    }
    sum /= sum32;

    if (sensor == SENSOR_WATER) {
        val = sum - water_offset;
        return (float)val / water_scale;
    } else {
        val = sum - food_offset;
        return (float)val / food_scale;
    }
}

void HX711init() {
    DDRB |= 0x0A;  // PB1, PB3 출력
    DDRB &= ~0x05; // PB0, PB2 입력

    WATER_SCK_1; FOOD_SCK_1;
    delay_us(100);
    WATER_SCK_0; FOOD_SCK_0;

    setOffset(SENSOR_WATER);
    setOffset(SENSOR_WATER);
    setOffset(SENSOR_FOOD);
    setOffset(SENSOR_FOOD);
}

//=============================================================================
// 센서 읽기 함수 (동작 중 실시간 갱신용)
//=============================================================================
void read_all_sensors(void) {
    long adc_sum = 0;
    int i = 0;

    // Water_H_sensor (PD5)
    if (PIND & 0b00100000) water_h_val = 1;
    else water_h_val = 0;

    // Food_H_sensor (ADC0) 10회 평균
    adc_sum = 0;
    for(i = 0; i < 10; i++) {
        adc_sum += adc_data[0];
        delay_us(100);
    }
    food_h_val = (int)(adc_sum / 10);

    // 무게 측정
    waterF_weight = getGram(SENSOR_WATER, 2);
    water_weight = (int)(waterF_weight * (-1));
    if (water_weight < 0){
        water_weight = 0;
        water_h_val = 0;
        //start_mode = 0;
        PORTE.2=0;
        PORTD &= ~0b00010000;
    }

    foodF_weight = getGram(SENSOR_FOOD, 2);
    food_weight = (int)(foodF_weight * (-1));
    if (food_weight < 0)food_weight = 0;



    // LCD 출력 갱신
    lcd_gotoxy(0, 0);
    sprintf(lcd_text, "M%d Ww%3d S%3d H%d",start_mode,water_weight,water_w_set,water_h_val);
    lcd_puts(lcd_text);

    lcd_gotoxy(0, 1);
    sprintf(lcd_text, "N%d F%3d H%3dA%dB%d",nfc_mode,food_weight,food_h_val,pill_a_count,pill_b_count);
    lcd_puts(lcd_text);
}

//=============================================================================
// 스텝모터 제어 함수
//=============================================================================
void step_motor1_45deg(char dir)
{
    int i;
    for(i=0; i<512; i++) {
        U08 idx = (dir == 1) ? (i % 8) : (7 - (i % 8));
        PORTA = (PORTA & 0xF0) | step_seq[idx];
        delay_ms(2);
    }
}

void step_motor2_45deg(char dir)
{
    int i;
    for(i=0; i<512; i++) {
        U08 idx = (dir == 1) ? (i % 8) : (7 - (i % 8));
        PORTA = (PORTA & 0x0F) | (step_seq[idx] << 4);
        delay_ms(2);
    }
}

//=============================================================================
// 패킷 명령 처리 함수 (wx200y1z2 형식)
//=============================================================================
void process_packet_command(char *buf) {
    char *x_ptr, *y_ptr, *z_ptr;
    int y_num = 0, z_num = 0;
    int k;

    // 'x', 'y', 'z' 위치 찾기
    x_ptr = strchr(buf, 'x');
    y_ptr = strchr(buf, 'y');
    z_ptr = strchr(buf, 'z');

    if (x_ptr != NULL) food_w_set = atoi(x_ptr + 1);
    if (y_ptr != NULL) y_num = atoi(y_ptr + 1);
    if (z_ptr != NULL) z_num = atoi(z_ptr + 1);

    // 자동 모드일 때만 실행
    if (start_mode == 1) {
        // 1. 사료 모터 제어 (x 값 처리)
        // 덮게 열림
        if(nfc_mode==1){
            PORTE.6=0; PORTE.7=1; delay_ms(5000);
            nfc_mode=0;
        }

        while (1) {
            read_all_sensors(); // 실시간 센서 확인

            // 감지 센서 수치 부족 시 중지 및 LED ON [290]
            if (food_h_val < food_h_val_set) {
                PORTE.4 = 0; PORTE.5 = 0; // 모터 정지
                PORTD &= ~0b01000000;    // FOOD LED ON (Active Low)
                break;
            } else {
                PORTD |= 0b01000000;     // FOOD LED OFF
            }

            // 목표 무게 도달 시 종료
            if (food_weight >= food_w_set) {
                PORTE.4 = 0; PORTE.5 = 0; // 모터 정지
                break;
            }

            // 모터 회전
            PORTE.4 = 0; PORTE.5 = 1;
        }

        // 2. 스텝모터 1 (y 값 처리)
        for (k = 0; k < y_num; k++) {
            step_motor1_45deg(0);
            if (pill_a_count > 0) pill_a_count--;

            if (pill_a_count == 0) {
                PORTD &= ~0b00000001; // PILL1 LED ON
            } else {
                PORTD |= 0b00000001;  // PILL1 LED OFF
            }
            read_all_sensors();
        }

        // 3. 스텝모터 2 (z 값 처리)
        for (k = 0; k < z_num; k++) {
            step_motor2_45deg(0);
            if (pill_b_count > 0) pill_b_count--;

            if (pill_b_count == 0) {
                PORTD &= ~0b00000010; // PILL2 LED ON
            } else {
                PORTD |= 0b00000010;  // PILL2 LED OFF
            }
            read_all_sensors();
        }
    }
}

//---------------------------------------------------------------------
// 메인 함수
//---------------------------------------------------------------------
void main(void) {
    DDRA = 0xFF; PORTA = 0x00; // 스텝모터 포트 출력

    // PORTD 설정 (LED: PD0, PD1, PD4, PD6 출력 / 센서: PD5 입력)
    DDRD |= 0b01010011;
    DDRD &= ~0b00100000;
    PORTD |= 0b01010011; // LED Active Low OFF

    // PORTE 설정 (모터 제어: PE2~PE7 출력)
    DDRE |= 0b11111100;
    PORTE &= ~0b11111100;

    // Timer3 Fast PWM 모드
    TCCR3A = 0x81;
    TCCR3B = 0x0B;
    OCR3AL = 120;
    OCR3AH = 0;

    // ADC 초기화
    ADMUX = FIRST_ADC_INPUT | (ADC_VREF_TYPE & 0xff);
    ADCSRA = 0xCC;

    // UART0 (블루투스: 9600) / UART1 (NFC: 115200)
    UCSR0A = 0x00; UCSR0B = 0x98; UCSR0C = 0x06; UBRR0H = 0x00; UBRR0L = 103;
    UCSR1A = 0x00; UCSR1B = 0x98; UCSR1C = 0x06; UBRR1H = 0x00; UBRR1L = 8;

    HX711init();

    lcd_init(16);
    lcd_clear();

    delay_ms(100);
    send_pn532_cmd(PN532_WAKEUP, sizeof(PN532_WAKEUP));
    delay_ms(300);

    #asm("sei")

    while (1) {
        // 데이터전송, 자동 문개패 , 수신데이터 페킷 설정완료 , 새부 수치 수정 데이터 추가해야함
        // water_w_set   food_h_val_set
        // 모든데이터초기화 start_mode=0; pill_a_count = 7; pill_b_count = 7; nfc_mode=0;
        //              PORTD |= 0b01010011; water_w_set=100; food_h_val_set=290;
        // 0정지 시작  start_mode=0  start_mode=1
        // 고정변수 수정하기 - water_w_set   food_h_val_set
        // 0덮게열기- 닫기     nfc_mode=0; nfc_mode=1;
        // 0알약,사료,물,보충완료= PORTD |= 0b01010011; pill_a_count = 7; pill_b_count = 7;

        // 1. NFC 탐색
        send_pn532_cmd(PN532_INLIST, sizeof(PN532_INLIST));

        // 2. 전체 센서 상태 및 무게 측정
        read_all_sensors();

        // 3. 자동 모드 제어 (start_mode = 1)
        if (start_mode == 1) {
            // 물 센서(water_h_val) 확인
            // 물그릇을 덜어내면 물이없는걸로판단해서 물을 공급하는 오류 방지
            if (water_weight < 0){
                water_weight = 0;
                water_h_val = 0;
                //start_mode = 0;
                PORTE.2=0;
                PORTD &= ~0b00010000;
            }

            //물이없으면 펌프정지
            if (water_h_val == 0) {
                PORTE.2 = 0;          // 물이 없으므로 펌프 정지
                PORTD &= ~0b00010000; // WATER LED ON (Active Low)
            } else {
            //물이있을때 무게판단후 물공급
                PORTD |= 0b00010000;  // WATER LED OFF
                if(water_h_in==0){
                    // 수위 수치 조건 판단 (water_w_set)
                    if (water_weight < water_w_set) {
                        PORTE.2 = 1;      // 펌프 동작
                    } else {
                        PORTE.2 = 0;      // 펌프 정지
                        water_h_in=1;     //물공급후 기준치보다 30작을때까지 물공급안함
                    }
                }else{
                    if(water_weight < water_w_set-30){
                    //기준치보다 30이하일때 물공급.
                        water_h_in=0;
                        water_eat++;
                    }
                }
            }


            // [덮개 모터]
            if (nfc_mode == 0) {
                PORTE.6=0; PORTE.7=1;  // 열림
            } else{
                PORTE.6=1; PORTE.7=0;  // 닫힘
            }

        }

        // 4. 수신 패킷 처리 (wx200y1z2 등)
        if (packet_ready) {
            process_packet_command(rx_buffer);
            packet_ready = 0;
        }

        // 5. 단일 문자 블루투스 명령 처리
        // 물이있으면 1 없으면 0// 펌프모터가 H일때 동작 // 사료 L,H 일때 시계반대방향회전 //덭게HL일떄닫힘
        // 스텝모터 1일때 시계반대 0일때 시계방향 //
        if (rx_cmd != 0) {
            switch(rx_cmd) {
                // [사료지급 모터]
                case 'a': PORTE.4=0; PORTE.5=1; break;  // 동작 (PWM은 Timer3가 PE3 핀으로 자동 출력중)
                case 'b': PORTE.4=0; PORTE.5=0; break;  // 정지

                // [펌프 모터]
                case 'c': PORTE.2=1; break;  // 동작
                case 'd': PORTE.2=0; break;  // 정지

                //0 [덮개 모터]
                case 'e': PORTE.6=1; PORTE.7=0; nfc_mode=1; break;   // 닫힘
                case 'f': PORTE.6=0; PORTE.7=1; nfc_mode=0; break;  // 열림
                case 'g': PORTE.6=0; PORTE.7=0; break; // 정지

                // [스텝 모터]
                case 'h': step_motor1_45deg(0); break;
                case 'i': step_motor2_45deg(0); break;

                //0 [모든설정 초기화]
                case 'j': {
                    start_mode=0; pill_a_count = 7; pill_b_count = 7; nfc_mode=0;
                    PORTD |= 0b01010011; water_w_set=100; food_h_val_set=290; water_eat=0;
                    break;
                }
                /*
                // [LED 제어 (Active Low)]
                case 'l': PORTD &= ~0b00000001; break; // LED_PILL1 ON
                case 'm': PORTD |=  0b00000001; break; // LED_PILL1 OFF
                case 'v': PORTD &= ~0b00000010; break; // LED_PILL2 ON
                case 'o': PORTD |=  0b00000010; break; // LED_PILL2 OFF
                case 'p': PORTD &= ~0b00010000; break; // LED_WATER ON
                case 'q': PORTD |=  0b00010000; break; // LED_WATER OFF
                case 'r': PORTD &= ~0b01000000; break; // LED_FOOD ON
                case 's': PORTD |=  0b01000000; break; // LED_FOOD OFF
                */

                //0 [모드 변경]
                case 'k': {
                    if(start_mode == 0){
                        start_mode=1;
                    }else{
                        start_mode=0;
                    }
                }

                //0 [알약,사료,물,보충완료]// LED끄기 초기화
                case 'm': PORTD |= 0b01010011; pill_a_count = 7; pill_b_count = 7; break;
                //0 [물량 설정]
                case 'o': water_w_set=water_w_set+10; break;
                case 'p': water_w_set=water_w_set-10; break;
            }
            rx_cmd = 0;
        }

        // 5. [APP으로 데이터 전송] data=16 개
        sprintf(str,"D"); TX0_STR(str);
        //start_mode
        sprintf(str,"%d",start_mode ); TX0_STR(str);
        //water_w_set
        if(water_w_set>=100){sprintf(str,"%3d",water_w_set);TX0_STR(str);}
        //else if((water_w_set>=10)&&(water_w_set<100)){sprintf(str,"0%2d",water_w_set); TX0_STR(str);}
        else if(water_w_set>=10){sprintf(str,"0%2d",water_w_set); TX0_STR(str);}
        else{sprintf(str,"00%d",water_w_set);TX0_STR(str);}
        //water_weight
        if(water_weight>=100){sprintf(str,"%3d",water_weight);TX0_STR(str);}
        else if(water_weight>=10){sprintf(str,"0%2d",water_weight); TX0_STR(str);}
        else{sprintf(str,"00%d",water_weight);TX0_STR(str);}
        if(food_weight>=100){sprintf(str,"%3d",food_weight);TX0_STR(str);}
        else if(food_weight>=10){sprintf(str,"0%2d",food_weight); TX0_STR(str);}
        else{sprintf(str,"00%d",food_weight);TX0_STR(str);}
        //pill_a_count
        sprintf(str,"%d",pill_a_count ); TX0_STR(str);
        //pill_b_count
        sprintf(str,"%d",pill_b_count ); TX0_STR(str);
        //water_h_val
        sprintf(str,"%d",water_h_val ); TX0_STR(str);
        //nfc_mode
        sprintf(str,"%d",nfc_mode ); TX0_STR(str);
        //food_h_val
        //(food_h_val<food_h_val_set)=0 사료부족, 1일때 사료 충분
        if(food_h_val<food_h_val_set){sprintf(str,"0"); TX0_STR(str);}
        else{sprintf(str,"1"); TX0_STR(str);}
        //water_eat
        if(water_eat>=10){sprintf(str,"%2d",water_eat ); TX0_STR(str);}
        else{sprintf(str,"0%d",water_eat ); TX0_STR(str);}
        delay_ms(150);

    } //end while
}