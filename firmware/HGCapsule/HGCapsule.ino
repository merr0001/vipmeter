// HG 진동 캡슐 펌웨어 v1.2  (M5Stack Capsule · ESP32-S3 · BMI270)
// 가속도 원본(800Hz)을 블루투스로 진동지기 앱에 보냄. 앱이 기존 규칙으로 RMS 계산.
//
// BLE 서비스 4e8f0001-6b2a-4c1e-9d3a-5a17c0de0001
//  0002 RAW  (notify) : [0]=0xA1 [1]=n [2..5]=u32 첫 샘플 번호 [6..9]=float 실제 샘플 Hz [10..]= n×(int16 x,y,z) 4096=1g
//        [0]=0xB1 (1초마다) 회전(자이로) [1..12]=float×3 흔들림 RMS °/s [13..24]=float×3 평균 °/s [25..28]=u32 샘플 번호
//  0003 CTRL (write)  : "S1" 측정 시작, "S0" 멈춤, "B" 삐 소리
//  0004 STAT (read/notify): "bat=87;mv=3950;ver=1.0;fs=800.12;on=1"
#include <M5Unified.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <esp_mac.h>

#define SVC   "4e8f0001-6b2a-4c1e-9d3a-5a17c0de0001"
#define C_RAW "4e8f0002-6b2a-4c1e-9d3a-5a17c0de0001"
#define C_CTL "4e8f0003-6b2a-4c1e-9d3a-5a17c0de0001"
#define C_STA "4e8f0004-6b2a-4c1e-9d3a-5a17c0de0001"
static const char* VER = "1.2";
static const int NPK = 36;            // 패킷당 샘플 수 (226바이트)

static uint8_t imuAddr = 0x69;
static BLECharacteristic *cRaw, *cSta;
static volatile bool connected = false, streaming = false, wantBeep = false;
static uint32_t sampleIdx = 0, fsStartIdx = 0; static uint64_t fsStartUs = 0; static float fsEst = 800.0f;
static int16_t pk[NPK * 3]; static int pkN = 0; static uint32_t pkFirst = 0;
static uint32_t lastStat = 0, lastActive = 0;
static double gs[3], gq[3]; static uint32_t gn = 0;
static const float GLSB = 65.536f;   // ±500°/s

static bool wr(uint8_t reg, uint8_t v) { return M5.In_I2C.writeRegister8(imuAddr, reg, v, 400000); }
static bool rd(uint8_t reg, uint8_t* b, size_t n) { return M5.In_I2C.readRegister(imuAddr, reg, b, n, 400000); }

static void imuStream(bool on) {
  if (on) {
    wr(0x7D, 0x06); delay(2);          // PWR_CTRL: 가속도 + 자이로
    wr(0x40, 0xAB);                     // ACC_CONF: ODR 800Hz, normal, 성능 필터
    wr(0x41, 0x02);                     // ACC_RANGE: ±8g
    wr(0x42, 0xEB);                     // GYR_CONF: ODR 800Hz, normal, 저잡음
    wr(0x43, 0x02);                     // GYR_RANGE: ±500°/s
    wr(0x48, 0x00);                     // FIFO_CONFIG_0: 꽉 차면 덮어쓰기
    wr(0x49, 0xC0);                     // FIFO_CONFIG_1: 가속도+자이로, 헤더 없음 (프레임 = 자이로6 + 가속도6)
    wr(0x7E, 0xB0);                     // CMD: FIFO 비우기
    delay(2);
    sampleIdx = 0; pkN = 0; fsStartIdx = 0; fsStartUs = esp_timer_get_time(); gn = 0; for (int k = 0; k < 3; k++) gs[k] = gq[k] = 0;
  } else {
    wr(0x49, 0x00);
    wr(0x7D, 0x00);                     // 센서 끔 (배터리 절약)
  }
}

static void readFifo() {
  uint8_t lb[2]; if (!rd(0x24, lb, 2)) return;
  int len = (lb[0] | (lb[1] << 8)) & 0x3FFF;
  len -= len % 12;
  static uint8_t buf[240];
  while (len > 0) {
    int n = len > 240 ? 240 : len;
    if (!rd(0x26, buf, n)) return;
    len -= n;
    for (int i = 0; i + 12 <= n; i += 12) {
      int16_t g0 = (int16_t)(buf[i] | (buf[i+1] << 8)), g1 = (int16_t)(buf[i+2] | (buf[i+3] << 8)), g2 = (int16_t)(buf[i+4] | (buf[i+5] << 8));
      int16_t x = (int16_t)(buf[i+6] | (buf[i+7] << 8)), y = (int16_t)(buf[i+8] | (buf[i+9] << 8)), z = (int16_t)(buf[i+10] | (buf[i+11] << 8));
      if (x == -32768 && y == -32768 && z == -32768) continue;   // 빈 프레임
      float gv[3] = { g0 / GLSB, g1 / GLSB, g2 / GLSB };
      for (int k = 0; k < 3; k++) { gs[k] += gv[k]; gq[k] += (double)gv[k] * gv[k]; }
      if (++gn >= 800) {                                          // 1초마다 회전 흔들림 요약
        uint8_t o[29]; o[0] = 0xB1; float r[3], m[3];
        for (int k = 0; k < 3; k++) { m[k] = gs[k] / gn; double v = gq[k] / gn - (double)m[k] * m[k]; r[k] = v > 0 ? sqrt(v) : 0; gs[k] = gq[k] = 0; }
        memcpy(o+1, r, 12); memcpy(o+13, m, 12); uint32_t si = sampleIdx; memcpy(o+25, &si, 4); gn = 0;
        if (connected) { cRaw->setValue(o, sizeof(o)); cRaw->notify(); }
      }
      if (pkN == 0) pkFirst = sampleIdx;
      pk[pkN*3] = x; pk[pkN*3+1] = y; pk[pkN*3+2] = z; pkN++; sampleIdx++;
      if (pkN == NPK) {
        uint8_t out[10 + NPK*6]; out[0] = 0xA1; out[1] = NPK;
        memcpy(out+2, &pkFirst, 4); memcpy(out+6, &fsEst, 4); memcpy(out+10, pk, NPK*6);
        if (connected) { cRaw->setValue(out, sizeof(out)); cRaw->notify(); }
        pkN = 0;
      }
    }
  }
  // 실제 샘플 속도 (ESP32 수정 시계 기준)
  uint64_t now = esp_timer_get_time(); double sec = (now - fsStartUs) / 1e6;
  if (sec > 2.0) fsEst = (float)((sampleIdx - fsStartIdx) / sec);
  if (sec > 60.0) { fsStartIdx = sampleIdx - (uint32_t)(fsEst * 30); fsStartUs = now - 30000000ULL; }   // 최근 약 30초 기준으로 갱신
}

static void sendStat() {
  char s[96];
  snprintf(s, sizeof(s), "bat=%d;mv=%d;ver=%s;fs=%.2f;on=%d", (int)M5.Power.getBatteryLevel(), (int)M5.Power.getBatteryVoltage(), VER, fsEst, streaming ? 1 : 0);
  cSta->setValue((uint8_t*)s, strlen(s)); if (connected) cSta->notify();
}

class SrvCb : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override { connected = true; wantBeep = true; lastActive = millis(); }
  void onDisconnect(BLEServer* s) override { connected = false; streaming = false; imuStream(false); BLEDevice::startAdvertising(); }
};
class CtlCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    if (v == "S1") { streaming = true; imuStream(true); }
    else if (v == "S0") { streaming = false; imuStream(false); }
    else if (v == "B") wantBeep = true;
    lastActive = millis();
  }
};

void setup() {
  auto cfg = M5.config(); cfg.internal_imu = true; cfg.internal_spk = true;
  M5.begin(cfg);
  setCpuFrequencyMhz(80);               // 배터리 절약 (측정·블루투스는 80MHz로 충분)
  uint8_t id = 0;
  imuAddr = 0x69; if (!rd(0x00, &id, 1) || id != 0x24) { imuAddr = 0x68; rd(0x00, &id, 1); }
  imuStream(false);

  uint8_t mac[6]; esp_read_mac(mac, ESP_MAC_BT);
  char name[20]; snprintf(name, sizeof(name), "HG-CAP-%02X%02X", mac[4], mac[5]);
  BLEDevice::init(name);
  BLEDevice::setMTU(247);
  BLEServer* srv = BLEDevice::createServer(); srv->setCallbacks(new SrvCb());
  BLEService* svc = srv->createService(SVC);
  cRaw = svc->createCharacteristic(C_RAW, BLECharacteristic::PROPERTY_NOTIFY); cRaw->addDescriptor(new BLE2902());
  BLECharacteristic* cCtl = svc->createCharacteristic(C_CTL, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR); cCtl->setCallbacks(new CtlCb());
  cSta = svc->createCharacteristic(C_STA, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY); cSta->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising(); adv->addServiceUUID(SVC); adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  M5.Speaker.tone(2600, 120); delay(150); M5.Speaker.tone(3400, 120);   // 켜짐 알림
  sendStat(); lastActive = millis();
}

void loop() {
  M5.update();
  if (streaming) readFifo();
  if (wantBeep) { wantBeep = false; M5.Speaker.tone(3400, 90); }
  uint32_t now = millis();
  if (now - lastStat > 5000) { lastStat = now; sendStat(); }
  // 버튼: 짧게 = 삐(배터리 많으면 2번), 2초 길게 = 전원 끄기
  if (M5.BtnA.wasClicked()) { int b = M5.Power.getBatteryLevel(); M5.Speaker.tone(3000, 80); if (b >= 50) { delay(140); M5.Speaker.tone(3000, 80); } }
  if (M5.BtnA.pressedFor(2000)) { M5.Speaker.tone(1800, 300); delay(400); M5.Power.powerOff(); }
  // 30분 동안 연결이 없으면 자동으로 꺼짐
  if (connected) lastActive = now;
  else if (now - lastActive > 30UL * 60 * 1000) { M5.Speaker.tone(1800, 300); delay(400); M5.Power.powerOff(); }
  delay(streaming ? 8 : 50);
}
