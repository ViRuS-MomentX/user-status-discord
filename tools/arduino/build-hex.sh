#!/bin/sh
# Собирает прошивку подставки (virus_stand.ino) в virus_stand.hex для Arduino Nano (ATmega328P),
# которую V.I.R.U.S. заливает в плату сам — без Arduino IDE.
# Нужны пакеты: gcc-avr avr-libc binutils-avr arduino-core-avr (Debian/Ubuntu).
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RES=$ROOT/manager/src/main/resources/ru/virus/manager
A=/usr/share/arduino/hardware/arduino/avr
B=$(mktemp -d)
F="-mmcu=atmega328p -DF_CPU=16000000L -DARDUINO=10806 -DARDUINO_AVR_NANO -DARDUINO_ARCH_AVR -Os -ffunction-sections -fdata-sections -I$A/cores/arduino -I$A/variants/eightanaloginputs -I$A/variants/standard -I$A/libraries/EEPROM/src"
(echo '#include <Arduino.h>'; cat "$RES/virus_stand.ino") > "$B/sketch.cpp"
for f in "$A"/cores/arduino/*.c; do avr-gcc $F -c "$f" -o "$B/$(basename "$f").o"; done
for f in "$A"/cores/arduino/*.cpp "$B/sketch.cpp"; do
  avr-g++ $F -std=gnu++11 -fno-exceptions -fno-threadsafe-statics -c "$f" -o "$B/$(basename "$f").o"
done
avr-gcc -mmcu=atmega328p -Os -Wl,--gc-sections "$B"/*.o -o "$B/stand.elf" -lm
avr-objcopy -O ihex -R .eeprom "$B/stand.elf" "$RES/virus_stand.hex"
avr-size "$B/stand.elf"
rm -rf "$B"
