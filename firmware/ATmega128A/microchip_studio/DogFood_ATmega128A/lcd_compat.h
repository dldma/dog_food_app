#ifndef LCD_COMPAT_H_
#define LCD_COMPAT_H_

#include <stdint.h>

// CodeVisionAVR lcd.h 호환 최소 API
// 원본 배선: PORTC
// PC0=RS, PC1=RW, PC2=EN, PC4~PC7=DB4~DB7
void lcd_init(uint8_t columns);
void lcd_clear(void);
void lcd_gotoxy(uint8_t x, uint8_t y);
void lcd_puts(const char *text);

#endif
