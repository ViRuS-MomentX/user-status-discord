// V.I.R.U.S. — подставка сна.
// Ставишь фигурку — компьютер «засыпает» (процессы, подсветка и экраны гаснут),
// убираешь — всё возвращается. Плата: Arduino Nano/Uno, связь с V.I.R.U.S. по USB.
//
// Датчик — любой (можно несколько):
//  М) Магнит в фигурке + АНАЛОГОВЫЙ датчик Холла (KY-035 «Analog Hall» или
//     KY-024 «Linear Hall» из 37 Sensor Kit) под столом. Плата меряет силу
//     поля сама — работает сквозь толстую столешницу, любым полюсом магнита.
//     KY-035: S → A0, средний (+) → 5V, − → GND.
//     KY-024: A0 → A0, + → 5V, G → GND (D0 не нужен).
//  0) RFID (лучше всего, есть в GyverKIT EXTRA): брелок или карта — в фигурке,
//     считыватель RC522 — под площадкой. Реагирует только на метку, сквозь
//     дерево или пластик до ~3 см. RC522: SDA → D10, SCK → D13, MOSI → D11,
//     MISO → D12, RST → D9, 3.3V → 3.3V (не 5V!), GND → GND.
//     Нужна библиотека MFRC522 (есть в «Библиотеки к набору» GyverKIT или в
//     Менеджере библиотек Arduino IDE) и USE_RFID 1 ниже.
//  1) Весы: тензодатчик на 1–5 кг + модуль HX711.
//     HX711: VCC → 5V, GND → GND, DT → D2, SCK → D3.
//     Тензодатчик → HX711: красный E+, чёрный E-, белый A-, зелёный A+.
//  2) Контакт на D4: кнопка или концевик между D4 и GND, либо модуль
//     с выходом S (датчик Холла KY-003 + магнит в фигурке, датчик
//     препятствия смотрит вверх в отверстие): S → D4, + → 5V, − → GND.
//
// Раз в 0,2 с плата пишет: «VIRUS-STAND <граммы> <контакт> <магнит>»
// (граммы = -1, если весов нет; «контакт» 1 — метка RFID рядом или D4 замкнут;
// «магнит» — насколько поле на A0 отличается от «пусто», 0…512).
// Команды от V.I.R.U.S.: «t» — запомнить «пусто» (фигурку убрать),
// «c400» — калибровка весов: на подставке груз 400 г.

#define USE_RFID 0  // 1 — если используешь RFID (нужна библиотека MFRC522)

#include <EEPROM.h>
#if USE_RFID
#include <SPI.h>
#include <MFRC522.h>
MFRC522 rfid(10, 9);  // SDA (SS) → D10, RST → D9
byte rfidMisses = 255;

// Метка рядом? WakeupA будит и «уснувшие» метки — так видно, что она лежит, а не только что положили
bool tagNear() {
  byte atqa[2];
  byte size = sizeof(atqa);
  rfid.PCD_StopCrypto1();
  MFRC522::StatusCode status = rfid.PICC_WakeupA(atqa, &size);

  if (status == MFRC522::STATUS_OK || status == MFRC522::STATUS_COLLISION) {
    rfid.PICC_ReadCardSerial();
    rfid.PICC_HaltA();
    rfidMisses = 0;
  } else if (rfidMisses < 255) {
    rfidMisses++;
  }

  return rfidMisses < 3;  // три промаха подряд — метку убрали (одиночный сбой не считается)
}
#endif

const byte DT_PIN = 2;
const byte SCK_PIN = 3;
const byte SWITCH_PIN = 4;
const byte HALL_PIN = A0;
const long NO_DATA = -2147483647L;
const int MAGIC = 0x5650;  // в памяти уже есть калибровка этой версии

struct Calibration {
  int magic;
  long offset;
  float scale;  // отсчётов HX711 на грамм
  int hallBase;  // A0 без фигурки
};

Calibration cal = {MAGIC, 0, 420.0, -1};

// Среднее по 16 замерам — меньше шума
int hallRead() {
  long sum = 0;
  for (byte i = 0; i < 16; i++) sum += analogRead(HALL_PIN);
  return sum / 16;
}
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
  // Подтяжка: без датчика на A0 там стабильные 1023, а не случайный шум
  pinMode(HALL_PIN, INPUT_PULLUP);
  digitalWrite(SCK_PIN, LOW);
#if USE_RFID
  SPI.begin();
  rfid.PCD_Init();
#endif
  delay(400);

  Calibration stored;
  EEPROM.get(0, stored);

  long now = average(10);
  scales = now != NO_DATA;

  if (stored.magic == MAGIC && stored.scale > 0.01 && stored.scale < 100000.0) {
    cal = stored;
  } else {
    // Первый запуск: считаем, что фигурки сейчас нет
    if (scales) cal.offset = now;
    cal.hallBase = hallRead();
    save();
  }
}

void command() {
  char c = Serial.read();

  if (c == 't') {
    if (scales) {
      long now = average(15);
      if (now != NO_DATA) cal.offset = now;
    }
    cal.hallBase = hallRead();
    save();
    Serial.println("VIRUS-STAND-OK tare");
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
#if USE_RFID
  closed = closed || tagNear();
#endif
  Serial.print("VIRUS-STAND ");
  Serial.print(grams);
  int hall = hallRead() - cal.hallBase;
  if (hall < 0) hall = -hall;  // любой полюс магнита (abs в Arduino — макрос, не для функций)
  Serial.print(' ');
  Serial.print(closed ? 1 : 0);
  Serial.print(' ');
  Serial.println(hall);
  delay(200);
}
