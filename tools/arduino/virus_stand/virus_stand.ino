// V.I.R.U.S. — подставка сна.
// Ставишь фигурку — компьютер «засыпает» (процессы, подсветка и экраны гаснут),
// убираешь — всё возвращается. Плата: Arduino Nano/Uno, связь с V.I.R.U.S. по USB.
//
// Датчик — любой из двух (можно оба):
//  1) Весы: тензодатчик на 1–5 кг + модуль HX711.
//     HX711: VCC → 5V, GND → GND, DT → D2, SCK → D3.
//     Тензодатчик → HX711: красный E+, чёрный E-, белый A-, зелёный A+.
//  2) Контакт: кнопка, концевик или геркон (и магнит в фигурке) между D4 и GND.
//
// Раз в 0,2 с плата пишет: «VIRUS-STAND <граммы> <контакт>»
// (граммы = -1, если весов нет; контакт 1 — замкнут).
// Команды от V.I.R.U.S.: «t» — обнулить весы (на подставке пусто),
// «c400» — калибровка: на подставке груз 400 г.

#include <EEPROM.h>

const byte DT_PIN = 2;
const byte SCK_PIN = 3;
const byte SWITCH_PIN = 4;
const long NO_DATA = -2147483647L;
const int MAGIC = 0x5649;  // «VI» — в памяти уже есть калибровка

struct Calibration {
  int magic;
  long offset;
  float scale;  // отсчётов HX711 на грамм
};

Calibration cal = {MAGIC, 0, 420.0};
bool scales = false;

long readRaw() {
  unsigned long started = millis();

  while (digitalRead(DT_PIN) == HIGH) {
    if (millis() - started > 200) return NO_DATA;  // HX711 не отвечает — весов нет
  }

  long value = 0;

  for (byte i = 0; i < 24; i++) {
    digitalWrite(SCK_PIN, HIGH);
    delayMicroseconds(1);
    value = (value << 1) | digitalRead(DT_PIN);
    digitalWrite(SCK_PIN, LOW);
    delayMicroseconds(1);
  }

  // 25-й импульс: канал A, усиление 128
  digitalWrite(SCK_PIN, HIGH);
  delayMicroseconds(1);
  digitalWrite(SCK_PIN, LOW);

  if (value & 0x800000L) value |= 0xFF000000L;
  return value;
}

long average(byte count) {
  long sum = 0;

  for (byte i = 0; i < count; i++) {
    long value = readRaw();
    if (value == NO_DATA) return NO_DATA;
    sum += value;
  }

  return sum / count;
}

void save() {
  EEPROM.put(0, cal);
}

void setup() {
  Serial.begin(115200);
  pinMode(DT_PIN, INPUT);
  pinMode(SCK_PIN, OUTPUT);
  pinMode(SWITCH_PIN, INPUT_PULLUP);
  digitalWrite(SCK_PIN, LOW);
  delay(400);

  Calibration stored;
  EEPROM.get(0, stored);

  long now = average(10);
  scales = now != NO_DATA;

  if (stored.magic == MAGIC && stored.scale > 0.01 && stored.scale < 100000.0) {
    cal = stored;
  } else if (scales) {
    cal.offset = now;  // первый запуск: считаем, что подставка пустая
    save();
  }
}

void command() {
  char c = Serial.read();

  if (c == 't' && scales) {
    long now = average(15);
    if (now != NO_DATA) {
      cal.offset = now;
      save();
      Serial.println("VIRUS-STAND-OK tare");
    }
  } else if (c == 'c' && scales) {
    long grams = Serial.parseInt();
    long now = average(15);

    if (grams > 0 && now != NO_DATA && now != cal.offset) {
      cal.scale = (float) (now - cal.offset) / grams;
      if (cal.scale < 0) cal.scale = -cal.scale;
      save();
      Serial.println("VIRUS-STAND-OK calibrated");
    }
  }
}

void loop() {
  while (Serial.available()) command();

  long grams = -1;

  if (scales) {
    long now = average(3);
    if (now != NO_DATA) {
      long value = (long) ((now - cal.offset) / cal.scale);
      grams = value < 0 ? -value : value;  // тензодатчик могли поставить «вверх ногами»
    }
  }

  bool closed = digitalRead(SWITCH_PIN) == LOW;
  Serial.print("VIRUS-STAND ");
  Serial.print(grams);
  Serial.print(' ');
  Serial.println(closed ? 1 : 0);
  delay(200);
}
