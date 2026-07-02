#include <ECG_inferencing.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include "esp_wifi.h"

/* --- Cấu hình chân và thông số --- */
#define ECG_PIN 34
#define INTERVAL_US 2000    // Tần số lấy mẫu gốc 500Hz (2000us)
#define R_THRESHOLD 0.80
#define MQTT_TOPIC_STATUS "duyNguyen/ecg/status"
#define MQTT_TOPIC_WAVE   "duyNguyen/ecg/wave"

/* --- Ngưỡng confidence theo từng nhãn (khai báo toàn cục) --- */
const float THRESHOLD[5] = {
  0.80,  // 0 - N  : recall cao, ngưỡng cao tránh false negative
  0.60,  // 1 - S  : recall thấp nhất, ngưỡng thấp để giảm bỏ sót
  0.65,  // 2 - V  : nguy hiểm nhất, ưu tiên không bỏ sót
  0.60,  // 3 - F  : recall thấp, ngưỡng thấp
  0.75   // 4 - Q  : recall cao, ngưỡng trung bình
};

/* --- Bộ đệm phẳng cho AI (125Hz - 187 mẫu) --- */
float input_buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE] = { 0 };

/* --- Các bộ đệm phục vụ cơ chế Cắt đoạn & Zero-padding mới --- */
#define MAX_RR_SAMPLES 187
float segment_buffer[MAX_RR_SAMPLES] = { 0 };
int segment_len = 0;
bool segment_active = false;

// Bộ đệm vòng cho MQTT hiển thị sóng (500Hz - 750 mẫu)
#define RING_BUFFER_SIZE 750
float ring_buffer[RING_BUFFER_SIZE] = { 0 };
volatile int write_index          = 0;
volatile int snapshot_write_index = 0;

/* --- Cấu hình WiFi & MQTT --- */
const char* ssid        = "tai_khoan_wifi";
const char* password    = "mat_khau_wifi";
const char* mqtt_server = "link_server_MQTT";
const int   mqtt_port   = 8883;
const char* mqtt_user   = "tai_khoan_cua_ban";
const char* mqtt_pass   = "mat_khau_cua_ban";

WiFiClientSecure espClient;
PubSubClient      client(espClient);

/* --- Biến FreeRTOS --- */
SemaphoreHandle_t xSemaphoreAI = NULL;
volatile bool     can_trigger  = true;

/* --- Cấu trúc dữ liệu gửi MQTT --- */
struct MQTT_Data {
  char          label[20];
  float         confidence;
  float         bpm;
  unsigned long rr;
};

/* --- Biến toàn cục hệ thống --- */
int                   sample_counter  = 0;
unsigned long         lastRTime       = 0;
float                 currentBPM      = 0;
volatile unsigned long lastRRInterval  = 0;

/* --- Quản lý Running Median cho RR Interval --- */
#define RR_HISTORY_SIZE 7
unsigned long rr_history[RR_HISTORY_SIZE] = {800, 800, 800, 800, 800, 800, 800}; // Khởi tạo danh định ~75BPM
int rr_history_idx = 0;

unsigned long getMedianRR() {
  unsigned long sorted[RR_HISTORY_SIZE];
  memcpy(sorted, rr_history, sizeof(sorted));
  for (int i = 0; i < RR_HISTORY_SIZE - 1; i++) {
    for (int j = 0; j < RR_HISTORY_SIZE - 1 - i; j++) {
      if (sorted[j] > sorted[j+1]) {
        unsigned long t = sorted[j]; sorted[j] = sorted[j+1]; sorted[j+1] = t;
      }
    }
  }
  return sorted[RR_HISTORY_SIZE / 2];
}

/* --- Hàm copy đoạn tín hiệu thực tế và thực hiện Zero-Padding vào AI Buffer --- */
void fillAIBufferFromSegment(float* src, int src_len, float* dest, int dest_max_len) {
  memcpy(dest, src, src_len * sizeof(float));
  if (dest_max_len > src_len) {
    memset(&dest[src_len], 0, (dest_max_len - src_len) * sizeof(float));
  }
}

/* -------------------------------------------------------
   Bộ lọc Notch 50Hz IIR bậc 2
   Fs = 500Hz, f0 = 50Hz, Q = 20
   ------------------------------------------------------- */
float x_mem[3] = {0, 0, 0};
float y_mem[3] = {0, 0, 0};

float notch50Hz(float x) {
  const float b0 =  0.9391f;
  const float b1 = -1.5195f;
  const float b2 =  0.9391f;
  const float a1 = -1.5195f;
  const float a2 =  0.8782f;

  x_mem[2] = x_mem[1]; x_mem[1] = x_mem[0]; x_mem[0] = x;
  y_mem[2] = y_mem[1]; y_mem[1] = y_mem[0];
  y_mem[0] = (b0 * x_mem[0]) + (b1 * x_mem[1]) + (b2 * x_mem[2])
           - (a1 * y_mem[1]) - (a2 * y_mem[2]);
  return y_mem[0];
}

/* -------------------------------------------------------
   TASK TỔNG HỢP (Nhân 0)
   - Xử lý AI 125Hz
   - Gửi mảng sóng 500Hz lên MQTT
   ------------------------------------------------------- */
void TaskAI_and_MQTT_Code(void* pvParameters) {
  float      local_inference_buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE];
  float      local_flat_wave_buffer[RING_BUFFER_SIZE];
  MQTT_Data  dataToSend;

  for (;;) {
    if (WiFi.status() != WL_CONNECTED) {
      WiFi.begin(ssid, password);
      vTaskDelay(pdMS_TO_TICKS(4000));
      continue;
    }

    if (!client.connected()) {
      client.connect("ESP32_ECG_Duy", mqtt_user, mqtt_pass);
      vTaskDelay(pdMS_TO_TICKS(1000));
      continue;
    }

    client.loop();

    if (xSemaphoreTake(xSemaphoreAI, pdMS_TO_TICKS(10)) == pdTRUE) {

      memcpy(local_inference_buffer, input_buffer, sizeof(input_buffer));

      int read_ptr = snapshot_write_index;
      for (int i = 0; i < RING_BUFFER_SIZE; i++) {
        local_flat_wave_buffer[i] = ring_buffer[read_ptr];
        read_ptr = (read_ptr + 1) % RING_BUFFER_SIZE;
      }

      dataToSend.bpm = currentBPM;
      dataToSend.rr  = lastRRInterval;

      if (client.connected() && dataToSend.bpm > 0) {
        String wavePayload = "{\"wave\":[";
        wavePayload.reserve(5500);
        for (int i = 0; i < RING_BUFFER_SIZE; i++) {
          wavePayload += String(local_flat_wave_buffer[i], 4);
          if (i < RING_BUFFER_SIZE - 1) wavePayload += ",";
        }
        wavePayload += "]}";
        client.publish(MQTT_TOPIC_WAVE, wavePayload.c_str());
      }

      signal_t           signal;
      ei_impulse_result_t result = { 0 };

      numpy::signal_from_buffer(
        local_inference_buffer,
        EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE,
        &signal
      );

      if (run_classifier(&signal, &result, false) == EI_IMPULSE_OK) {
        float    max_score = 0;
        int      winner    = 0;
        for (uint16_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
          if (result.classification[i].value > max_score) {
            max_score = result.classification[i].value;
            winner    = i;
          }
        }

        if (max_score >= THRESHOLD[winner]) {
          strncpy(dataToSend.label, result.classification[winner].label, 20);
          dataToSend.confidence = max_score;
        } else if (max_score >= 0.35f) {
          snprintf(dataToSend.label, sizeof(dataToSend.label),
                   "UNC_%s", result.classification[winner].label);
          dataToSend.confidence = max_score;
        } else {
          strcpy(dataToSend.label, "LOW_SIGNAL");
          dataToSend.confidence = 0;
        }
      } else {
        strcpy(dataToSend.label, "Error");
        dataToSend.confidence = 0;
      }

      if (client.connected()) {
        char payload[160];
        snprintf(payload, sizeof(payload),
                 "{\"status\":\"%s\", \"confidence\":%.2f, \"bpm\":%.1f, \"rr\":%lu}",
                 dataToSend.label,
                 dataToSend.confidence,
                 dataToSend.bpm,
                 dataToSend.rr);
        client.publish(MQTT_TOPIC_STATUS, payload);
      }

      can_trigger = true;
    }

    vTaskDelay(pdMS_TO_TICKS(1));
  }
}

/* -------------------------------------------------------
   SETUP
   ------------------------------------------------------- */
void setup() {
  Serial.begin(115200);
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  espClient.setInsecure();
  client.setBufferSize(6144);
  client.setServer(mqtt_server, mqtt_port);

  xSemaphoreAI = xSemaphoreCreateBinary();

  xTaskCreatePinnedToCore(
    TaskAI_and_MQTT_Code,
    "TaskAI_MQTT",
    30000,
    NULL,
    tskIDLE_PRIORITY,
    NULL,
    0
  );

  Serial.println(">>> He thong [AI 125Hz - Do thi 500Hz]: San sang!");
}

/* -------------------------------------------------------
   LOOP (Nhân 1)
   ------------------------------------------------------- */
void loop() {
  static uint64_t       lastSampleTime    = 0;
  static unsigned long  lastForcedTrigger = 0;
  static bool           hasSentZero       = false;

  if (micros() - lastSampleTime >= INTERVAL_US) {
    lastSampleTime = micros();

// 1. Đọc dữ liệu ADC thô và chuẩn hóa tĩnh ban đầu
    int   raw = analogRead(ECG_PIN);
    float volt_raw = notch50Hz((float)raw) / 4095.0f;
    // Chuẩn hóa Min-Max tùy biến: Chuyển dải thô [0.4, 1.0] thành dải mục tiêu [0.1, 1.0]
    float volt = 0.1f + 0.9f * ((volt_raw - 0.4f) / (1.0f - 0.4f));
    volt = constrain(volt, 0.0f, 1.0f);

    ring_buffer[write_index] = volt;

    sample_counter++;
    if (sample_counter >= 4) {
      sample_counter = 0;

      // Thu nạp tín hiệu liên tục vào bộ đệm phân đoạn
      if (segment_active && segment_len < MAX_RR_SAMPLES) {
        segment_buffer[segment_len] = volt;
        segment_len++;
      }

      if (volt > R_THRESHOLD) {
        unsigned long now = millis();
        unsigned long rr  = now - lastRTime;

        if (currentBPM == 0) {
          lastRTime      = now;
          currentBPM     = 1;
          hasSentZero    = false;
          segment_len    = 0;
          segment_active = true;

        } else if (rr > 375 && rr < 1500) {
          currentBPM     = 60000.0f / rr;
          lastRTime      = now;
          lastRRInterval = rr;
          hasSentZero    = false;

          rr_history[rr_history_idx] = rr;
          rr_history_idx = (rr_history_idx + 1) % RR_HISTORY_SIZE;
          
          unsigned long T_ms = getMedianRR();
          int targetLen = (int)((1.2f * T_ms) / 8.0f);
          if (targetLen > MAX_RR_SAMPLES) targetLen = MAX_RR_SAMPLES;

          // Cắt đoạn động và Zero-padding lập tức khi có đỉnh R kế tiếp kích hoạt
          if (can_trigger && segment_active && segment_len > 1) {
            int usedLen = (segment_len < targetLen) ? segment_len : targetLen;
            fillAIBufferFromSegment(segment_buffer, usedLen, input_buffer, EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE);
            snapshot_write_index = write_index;
            xSemaphoreGive(xSemaphoreAI);
            can_trigger = false;
          }

          // Cài đặt lại gốc phân đoạn mới bắt đầu từ mẫu đỉnh R này
          segment_buffer[0] = volt;
          segment_len = 1;
          segment_active = true;

        } else if (rr >= 1500) {
          lastRTime = now;
          segment_len = 0;
        }
      }
    }

    write_index = (write_index + 1) % RING_BUFFER_SIZE;

    if (millis() - lastRTime > 2000) {
      if (!hasSentZero) {
        currentBPM     = 0;
        lastRRInterval = 0;
        segment_len    = 0;
        segment_active = false;
        if (client.connected()) {
          char timeoutPayload[160];
          snprintf(timeoutPayload, sizeof(timeoutPayload),
                   "{\"status\":\"NO_SIGNAL\", \"confidence\":0.0, \"bpm\":0.0, \"rr\":0}");
          client.publish(MQTT_TOPIC_STATUS, timeoutPayload);
        }
        hasSentZero = true;
        Serial.println(">>> MAT TIN HIEU!");
      }
    }
  }

  if (millis() - lastForcedTrigger > 10000) {
    lastForcedTrigger = millis();
    if (can_trigger && currentBPM > 0) {
      int usedLen = (segment_len < EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) ? segment_len : EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE;
      fillAIBufferFromSegment(segment_buffer, usedLen, input_buffer, EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE);
      snapshot_write_index = write_index;
      xSemaphoreGive(xSemaphoreAI);
      can_trigger = false;
    }
  }
}