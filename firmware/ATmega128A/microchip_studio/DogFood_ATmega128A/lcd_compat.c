#include "config.h"
#include <avr/io.h>
#include <util/delay.h>
#include "lcd_compat.h"

#define LCD_RS PC0
#define LCD_RW PC1
#define LCD_EN PC2

static void lcd_pulse_enable(void)
{
    PORTC |= _BV(LCD_EN);
    _delay_us(1);
    PORTC &= (uint8_t)~_BV(LCD_EN);
    _delay_us(50);
}

static void lcd_write4(uint8_t high_nibble)
{
    PORTC = (PORTC & 0x0F) | (high_nibble & 0xF0);
    lcd_pulse_enable();
}

static void lcd_write(uint8_t value, uint8_t is_data)
{
    if (is_data) PORTC |= _BV(LCD_RS);
    else PORTC &= (uint8_t)~_BV(LCD_RS);

    // 원본 CodeVision 배선에는 RW가 PC1에 연결됨. 쓰기만 사용하므로 항상 LOW.
    PORTC &= (uint8_t)~_BV(LCD_RW);

    lcd_write4(value & 0xF0);
    lcd_write4((uint8_t)(value << 4));
}

static void lcd_command(uint8_t command)
{
    lcd_write(command, 0);
    if (command == 0x01 || command == 0x02) _delay_ms(2);
}

void lcd_init(uint8_t columns)
{
    (void)columns;

    DDRC |= 0xF7; // PC0,1,2,4,5,6,7 출력
    PORTC &= (uint8_t)~(_BV(LCD_RS) | _BV(LCD_RW) | _BV(LCD_EN));

    _delay_ms(40);

    // HD44780 4-bit 초기화
    lcd_write4(0x30);
    _delay_ms(5);
    lcd_write4(0x30);
    _delay_us(150);
    lcd_write4(0x30);
    lcd_write4(0x20);

    lcd_command(0x28); // 4-bit, 2-line, 5x8 font
    lcd_command(0x0C); // display on, cursor off
    lcd_command(0x06); // entry mode
    lcd_clear();
}

void lcd_clear(void)
{
    lcd_command(0x01);
}

void lcd_gotoxy(uint8_t x, uint8_t y)
{
    static const uint8_t row_addr[] = {0x00, 0x40, 0x14, 0x54};
    if (y > 3) y = 0;
    lcd_command((uint8_t)(0x80 | (row_addr[y] + x)));
}

void lcd_puts(const char *text)
{
    while (*text) {
        lcd_write((uint8_t)*text++, 1);
    }
}
