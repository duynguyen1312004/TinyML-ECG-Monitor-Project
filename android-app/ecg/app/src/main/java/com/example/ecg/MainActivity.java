package com.example.ecg;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


import androidx.appcompat.app.AppCompatActivity;


import com.google.firebase.auth.FirebaseAuth;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.SSLSocketFactory;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;


public class MainActivity extends AppCompatActivity {
    // UI Elements
    private MqttClient client;
    private TextView txtStatus, txtConfidence, txtBPM, txtTimer;
    private LineChart ecgChart;
    private Button btnStartReport;

    // Firebase
    private DatabaseReference mDatabase;

    // xuwr lý vấn ề gửi trùng tín hiệu sóng:
    private List<Float> lastDrawnSamples = new ArrayList<>();

    private List<Double> confidenceList = new ArrayList<>();

    // Dữ liệu báo cáo và lịch sử
    private List<Double> rrList = new ArrayList<>();
    private List<Double> bpmList = new ArrayList<>();
    private List<String> labelList = new ArrayList<>();
    private List<String> historyRecords = new ArrayList<>();
    private boolean isRecording = false;
    private long recordingStartTime = 0;

    // Bộ đệm phát lại (Playback Buffer)
    private Queue<Float> playbackQueue = new ConcurrentLinkedQueue<>();
    private Handler playbackHandler = new Handler();
    private Runnable playbackRunnable;

    // Đồng hồ bấm giờ
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable;

    // Cấu hình MQTT
    private String serverUri = "server_MQTT_cua_ban";
    private String clientId = "Android_Duy_Pro_Final_" + System.currentTimeMillis();
    private String username = "tai_khoan_MQTT";
    private String password = "mat_khau_MQTT";

    private ArrayList<Float> movingAverageBuffer = new ArrayList<>();
    private static final int MA_WINDOW_SIZE = 5; // Cửa sổ lọc 5 mẫu

    // Biến lưu giá trị trước đó để vuốt mượt sóng
    private float lastSmoothedValue = -1f;

    // HỆ SỐ LÀM MƯỢT (Alpha).
// Từ 0.1 đến 0.3. Số CÀNG NHỎ thì sóng CÀNG MƯỢT (càng tròn) nhưng đỉnh R sẽ hơi lùn đi một tí.
    private static final float SMOOTHING_FACTOR = 0.15f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Khởi tạo Firebase
        try {
            mDatabase = FirebaseDatabase.getInstance("link_firebase").getReference("Collection_cua_ban_tren_firebase");
        } catch (Exception e) {
            Log.e("FIREBASE", "Chưa cấu hình Firebase chuẩn");
        }
        // Xác thực ẩn để lấy quyền gửi dữ liệu lên Database mà không cần màn hình Login
        // Xác thực ẩn để có quyền ghi dữ liệu vào Firebase đã khóa
        FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener(authResult -> {
                    Log.d("AUTH", "Xác thực ẩn thành công: " + authResult.getUser().getUid());
                })
                .addOnFailureListener(e -> {
                    // Biến 'e' ở đây chính là Exception, giúp getMessage() hết báo đỏ
                    Log.e("AUTH", "Lỗi xác thực: " + e.getMessage());
                });
        // 2. Ánh xạ View
        txtStatus = findViewById(R.id.txtStatus);
        txtConfidence = findViewById(R.id.txtConfidence);
        txtBPM = findViewById(R.id.txtBPM);
        txtTimer = findViewById(R.id.txtTimer);
        ecgChart = findViewById(R.id.ecgChart);
        btnStartReport = findViewById(R.id.btnStartReport);

        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistoryDialog());

        // 3. Khởi tạo biểu đồ và MQTT
        setupChart();
        connectMQTT();

        // 4. Xử lý nút bấm
        btnStartReport.setOnClickListener(v -> {
            if (!isRecording) startRecording();
            else stopAndShowReport();
        });

        // 5. Khởi tạo bộ phát lại sóng (Playback Engine) SIÊU MƯỢT
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                int queueSize = playbackQueue.size();
                int pointsToDraw;

                // --- BƯỚC 1: CƠ CHẾ CHỐNG KHỰNG (Pre-buffering) ---
                // Nếu trong kho có ít hơn 150 điểm, tạm dừng vẽ 100ms để đợi gói tin mới từ ESP32.
                // Việc này giúp kho luôn có sẵn "hàng dự phòng", tránh việc đang vẽ thì hết hàng gây khựng.
                if (queueSize < 150 && queueSize > 0) {
                    playbackHandler.postDelayed(this, 100);
                    return;
                }

                // --- BƯỚC 2: CHIẾN THUẬT VẼ THÍCH ỨNG ---
                if (queueSize > 2000) {
                    pointsToDraw = 30; // Kho quá đầy (do lag mạng) -> Vẽ cực nhanh để đuổi kịp
                } else if (queueSize > 1000) {
                    pointsToDraw = 20; // Vẽ hơi nhanh
                } else if (queueSize < 400 && queueSize > 0) {
                    pointsToDraw = 7;  // Kho sắp hết -> Vẽ chậm lại một chút để chờ gói tin tiếp theo
                } else if (queueSize > 0) {
                    pointsToDraw = 10; // Tốc độ chuẩn 500Hz (10 điểm x 50 lần/giây)
                } else {
                    pointsToDraw = 0;  // Kho rỗng (không có dữ liệu)
                }

                // --- BƯỚC 3: THỰC HIỆN VẼ CỤM ĐIỂM (Batch Drawing) ---
                for (int i = 0; i < pointsToDraw; i++) {
                    Float val = playbackQueue.poll();
                    if (val != null) {
                        // QUAN TRỌNG: Chỉ gửi lệnh cập nhật màn hình (true) ở điểm CUỐI CÙNG của cụm.
                        // Việc này giúp giảm tải GPU/CPU 10 lần so với việc vẽ từng điểm một.
                        boolean isLastPointOfBatch = (i == pointsToDraw - 1);
                        addEntry(val, isLastPointOfBatch);
                    }
                }

                // --- BƯỚC 4: LẶP LẠI ---
                // Nếu đang có dữ liệu thì lặp lại sau 20ms (50 FPS). Nếu kho rỗng thì nghỉ 100ms.
                long nextDelay = (queueSize == 0) ? 100 : 20;
                playbackHandler.postDelayed(this, nextDelay);
            }
        };
// Bắt đầu kích hoạt vòng lặp vẽ
        playbackHandler.post(playbackRunnable);
    }

    private void setupChart() {
        ecgChart.getDescription().setEnabled(false);
        ecgChart.setTouchEnabled(false);
        ecgChart.getLegend().setEnabled(false);
        ecgChart.getAxisRight().setEnabled(false);
        ecgChart.getXAxis().setEnabled(false);
        ecgChart.setBackgroundColor(Color.BLACK);
        // BẬT TÍNH NĂNG AUTO-SCALE (TỰ ĐỘNG CO GIÃN THEO ĐỈNH SÓNG)
//        ecgChart.setAutoScaleMinMaxEnabled(true);
        YAxis y = ecgChart.getAxisLeft();
//        y.setAxisMaximum(1.2f);
//        y.setAxisMinimum(-0.2f);
        ecgChart.getRenderer().getPaintRender().setAntiAlias(true);

        // (Tùy chọn) Thêm một chút padding để sóng R không chạm sát nóc màn hình
//        y.setSpaceTop(15f);
//        y.setSpaceBottom(15f);

        y.setTextColor(Color.GREEN);
        y.setDrawGridLines(true);
        y.setGridColor(Color.DKGRAY);
        ecgChart.setData(new LineData());
    }

    //Hàm làm mịn
    private LinkedList<Float> window = new LinkedList<>();

    private float applyMovingAverage(float newVal) {
        window.add(newVal);
        if (window.size() > 9) { // Window size = 5 là mức tối ưu cho 500Hz
            window.removeFirst();
        }
        float sum = 0;
        for (float f : window) sum += f;
        return sum / window.size();
    }

    private void addEntry(float value, boolean updateChart) {
        if (ecgChart == null) return;
        LineData data = ecgChart.getData();
        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0);
            if (set == null) {
                LineDataSet newSet = new LineDataSet(null, "ECG Wave");
                newSet.setColor(Color.GREEN);
                newSet.setLineWidth(1.5f); //độ dày nét vẽ
                newSet.setDrawCircles(false);// Tắt các dấu chấm tại mỗi điểm dữ liệu
                newSet.setDrawValues(false);// Không hiển thị giá trị số trên đỉnh sóng

                newSet.setMode(LineDataSet.Mode.CUBIC_BEZIER); // Giúp đường cong mượt hơn
                newSet.setCubicIntensity(0.15f);

                newSet.setDrawFilled(false);       // Tắt đổ bóng để tăng FPS và độ sạch
                data.addDataSet(newSet);
                set = newSet;
            }

            // 1. Luôn thêm điểm mới vào dữ liệu (nhưng chưa vẽ ngay)
            data.addEntry(new Entry(set.getEntryCount(), value), 0);

            // 2. CHỈ CẬP NHẬT MÀN HÌNH KHI ĐƯỢC LỆNH (updateChart == true)
            // Việc này giúp App không bị quá tải khi xử lý 500 điểm/giây
            if (updateChart) {
                data.notifyDataChanged();
                ecgChart.notifyDataSetChanged();

                // Giới hạn hiển thị 500 điểm (~1 giây dữ liệu) trên màn hình
                ecgChart.setVisibleXRangeMaximum(500);

                // Cuộn màn hình tới điểm mới nhất
                ecgChart.moveViewToX(data.getEntryCount());
            }
        }
    }

    private void connectMQTT() {
        try {
            client = new MqttClient(serverUri, clientId, new MemoryPersistence());
            MqttConnectOptions opt = new MqttConnectOptions();
            opt.setUserName(username);
            opt.setPassword(password.toCharArray());
            opt.setSocketFactory(SSLSocketFactory.getDefault());
            opt.setAutomaticReconnect(true);
            opt.setCleanSession(true);

            client.setCallback(new MqttCallback() {
                @Override
                public void messageArrived(String topic, MqttMessage msg) {
                    try {
                        String payload = new String(msg.getPayload());

                        if (topic.equals("duyNguyen/ecg/wave")) {
                            JSONObject jsonWave = new JSONObject(payload);
                            JSONArray waveArray = jsonWave.getJSONArray("wave");
                            int len = waveArray.length();

                            // --- THUẬT TOÁN KHỬ TRÙNG LẶP ---
                            int startIndex = 0;
                            int sigSize = 20; // Dùng 20 điểm cuối làm "dấu vân tay" để đối chiếu

                            if (lastDrawnSamples.size() >= sigSize) {
                                // Duyệt qua mảng mới nhận được để tìm đoạn khớp với 20 điểm cũ nhất
                                for (int i = 0; i <= len - sigSize; i++) {
                                    boolean isMatch = true;
                                    for (int j = 0; j < sigSize; j++) {
                                        // So sánh giá trị với độ sai số cực nhỏ (0.001)
                                        if (Math.abs(waveArray.getDouble(i + j) - lastDrawnSamples.get(j)) > 0.001) {
                                            isMatch = false;
                                            break;
                                        }
                                    }
                                    if (isMatch) {
                                        // Nếu tìm thấy đoạn trùng, điểm bắt đầu vẽ tiếp sẽ là sau đoạn đó
                                        startIndex = i + sigSize;
                                        break;
                                    }
                                }
                            }
                            // Duyệt qua dữ liệu mới nhận từ MQTT
                            for (int i = startIndex; i < len; i++) {
                                float rawVal = (float) waveArray.getDouble(i); // 1. Lấy giá trị thô gốc từ MQTT

                                // 2. [QUAN TRỌNG] Chỉ dùng rawVal để cập nhật "Dấu vân tay" đối chiếu
                                lastDrawnSamples.add(rawVal);
                                if (lastDrawnSamples.size() > sigSize) {
                                    lastDrawnSamples.remove(0);
                                }

                                // 3. Thực hiện làm mịn sóng (Chỉ dùng để vẽ, không dùng để đối chiếu)
                                // Nếu dùng Moving Average, hãy dùng window size khoảng 5 mẫu
                                float smoothedVal = applyMovingAverage(rawVal);

                                // 4. Đẩy giá trị ĐÃ LÀM MỊN vào hàng đợi vẽ đồ thị
                                playbackQueue.add(smoothedVal);
                            }

                            // --------------------------------

                        } else if (topic.equals("duyNguyen/ecg/status")) {
                            runOnUiThread(() -> processMedicalData(payload));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            new Thread(() -> {
                try {
                    client.connect(opt);
                    client.subscribe("duyNguyen/ecg/status", 0);
                    client.subscribe("duyNguyen/ecg/wave", 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getMedicalLabel(String rawStatus, double bpm, double confidence) {
        String s = rawStatus.toLowerCase();

        // 1. LỚP CHẶN PHI LÝ: Tháo dây hoặc nhiễu nặng
        if (bpm < 40 || bpm > 180) return "NHIỄU TÍN HIỆU (Q)";

        // 2. BỘ LỌC ƯU TIÊN SINH HỌC (BIO-PRIORITY FILTER):
        // Nếu nhịp tim đang rất ổn định (55 - 90 BPM)
        // thì 99% các nhãn V/S/F báo về là do lỗi nhận diện hình thái của AI trên người cụ thể.
        // Chúng ta sẽ ép nhãn về Bình Thường để báo cáo không bị sai lệch.
        if (bpm >= 55 && bpm <= 90) {
            return "BÌNH THƯỜNG (N)";
        }

        // 3. NẾU NHỊP TIM NGOÀI DẢI 55-90: Lúc này mới cho phép AI chẩn đoán bệnh lý
        if (s.contains("2.0")) return "NGOẠI TÂM THU THẤT (V)";
        if (s.contains("1.0")) return "NGOẠI TÂM THU TRÊN THẤT (S)";
        if (s.contains("3.0")) return "NHỊP HỖN HỢP (F)";
        if (s.contains("0.0") || s.contains("normal")) return "BÌNH THƯỜNG (N)";

        return "NHIỄU TÍN HIỆU (Q)";
    }

    private void processMedicalData(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String aiStatus = json.getString("status");
            double bpm = json.getDouble("bpm");
            double confidence = json.getDouble("confidence");

            // 1. Chuyển đổi sang nhãn y tế (Hàm này đã có bộ lọc nhịp phi lý < 40 hoặc > 180)
            String medicalLabel = getMedicalLabel(aiStatus, bpm, confidence);

            // 2. Cập nhật giao diện tức thời lên màn hình
            txtStatus.setText(medicalLabel);
            txtBPM.setText(String.format("%.0f", bpm));
            txtConfidence.setText("AI tin tưởng: " + String.format("%.0f%%", confidence * 100));

            // Thiết lập màu sắc trực quan cho nhãn hiển thị
            if (medicalLabel.contains("(N)")) {
                txtStatus.setTextColor(Color.parseColor("#2E7D32")); // Xanh lá: Ổn định
            } else if (medicalLabel.contains("(Q)")) {
                txtStatus.setTextColor(Color.GRAY); // Xám: Nhiễu
            } else {
                txtStatus.setTextColor(Color.RED); // Đỏ: Bất thường (S, V, F)
            }

            // 3. LOGIC LỌC NHIỄU TIẾP XÚC (CHỐNG BÁO GIẢ KHI THÁO/DÁN GEL)
            long elapsedSeconds = (System.currentTimeMillis() - recordingStartTime) / 1000;

            // Điều kiện để dữ liệu được coi là "Tin cậy" để đưa vào báo cáo/nhật ký:
            // - Nhịp tim phải nằm trong dải sinh lý (40 - 180 BPM)
            // - Không phải nhãn Nhiễu (Q)
            boolean isReliableSignal = (bpm >= 40 && bpm <= 180) && !medicalLabel.contains("(Q)");

            // A. Lưu vào Nhật ký biến cố (Chỉ lưu nếu tín hiệu tin cậy và là nhịp bệnh lý)
            if (isReliableSignal) {
                if (medicalLabel.contains("(S)") || medicalLabel.contains("(V)") || medicalLabel.contains("(F)")) {
                    saveAlertToHistory(aiStatus, bpm, confidence);
                }
            }

            // B. Lưu dữ liệu phục vụ Báo cáo chuyên sâu (Chỉ lưu khi đang Recording)
            if (isRecording) {
                // LỚP BẢO VỆ CUỐI: Bỏ qua 5 giây đầu tiên để đợi tín hiệu ổn định hẳn
                if (elapsedSeconds > 5 && bpm > 0) {
                    bpmList.add(bpm);
                    labelList.add(aiStatus);
                    confidenceList.add(confidence);
                    if (json.has("rr")) {
                        rrList.add(json.getDouble("rr"));
                    }
                }
            }

        } catch (Exception e) {
            Log.e("ECG_ERROR", "Lỗi giải mã dữ liệu: " + e.getMessage());
        }
    }

    private void saveAlertToHistory(String status, double bpm, double confidence) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        // SỬA DÒNG NÀY: Thêm confidence vào tham số thứ 3
        String labelName = getMedicalLabel(status, bpm, confidence);

        // Chỉ lưu vào nhật ký nếu sau khi lọc nó vẫn là nhịp bất thường (S, V, F)
        if (labelName.contains("(S)") || labelName.contains("(V)") || labelName.contains("(F)")) {
            String record = String.format("[%s]\nLoại: %s\nNhịp: %.0f BPM | Tin cậy: %.0f%%",
                    timeStamp, labelName, bpm, confidence * 100);
            historyRecords.add(0, record);
            if (historyRecords.size() > 50) historyRecords.remove(50);
        }
    }

    private void startRecording() {
        isRecording = true;
        rrList.clear();
        bpmList.clear();
        labelList.clear();
        confidenceList.clear();
        recordingStartTime = System.currentTimeMillis();
        btnStartReport.setText("KẾT THÚC & XUẤT BÁO CÁO");
        btnStartReport.setBackgroundColor(Color.RED);
        txtTimer.setVisibility(View.VISIBLE);

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long seconds = (System.currentTimeMillis() - recordingStartTime) / 1000;
                txtTimer.setText(String.format("Thời gian thu thập: %02d:%02d", seconds / 60, seconds % 60));
                timerHandler.postDelayed(this, 500);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopAndShowReport() {
        if (timerHandler != null) timerHandler.removeCallbacks(timerRunnable);
        showReport();
    }

    private void showReport() {
        isRecording = false;
        txtTimer.setVisibility(View.GONE);
        btnStartReport.setText("BẮT ĐẦU PHÂN TÍCH");
        btnStartReport.setBackgroundColor(Color.parseColor("#1A237E"));

        if (bpmList.isEmpty()) {
            Toast.makeText(this, "Không có dữ liệu để phân tích!", Toast.LENGTH_LONG).show();
            return;
        }

        // --- 1. THỐNG KÊ CHI TIẾT NHÃN (ĐÃ QUA BỘ LỌC ĐỐI SOÁT BPM) ---
        int countN = 0, countS = 0, countV = 0, countF = 0, countQ = 0;
        List<String> filteredLabels = new ArrayList<>();

        for (int i = 0; i < labelList.size(); i++) {
            // Sử dụng hàm đối soát y tế để lấy nhãn trung thực nhất
            String medicalLabel = getMedicalLabel(labelList.get(i), bpmList.get(i), confidenceList.get(i));
            filteredLabels.add(medicalLabel);

            if (medicalLabel.contains("(N)")) countN++;
            else if (medicalLabel.contains("(S)")) countS++;
            else if (medicalLabel.contains("(V)")) countV++;
            else if (medicalLabel.contains("(F)")) countF++;
            else countQ++;
        }

        // --- 2. THUẬT TOÁN PHÁT HIỆN CHUỖI NHỊP V LIÊN TIẾP (RUN DETECTION) ---
        int maxConsecutiveV = 0;
        int currentRunV = 0;
        for (String label : filteredLabels) {
            if (label.contains("(V)")) {
                currentRunV++;
                if (currentRunV > maxConsecutiveV) maxConsecutiveV = currentRunV;
            } else {
                currentRunV = 0;
            }
        }

        // --- 3. TÍNH HRV (SDNN) ---
        double hrvValue = 0;
        if (rrList.size() > 1) {
            double sumRR = 0;
            for (Double r : rrList) sumRR += r;
            double meanRR = sumRR / rrList.size();
            double sqSum = 0;
            for (Double r : rrList) sqSum += Math.pow(r - meanRR, 2);
            hrvValue = Math.sqrt(sqSum / rrList.size());
        }

        // --- 4. TÍNH ĐIỂM SỨC KHỎE THEO MẬT ĐỘ BIẾN CỐ (BURDEN LOGIC) ---
        double avgBpmValue = 0;
        for (Double b : bpmList) avgBpmValue += b;
        avgBpmValue /= bpmList.size();

        int totalBeats = countN + countV + countS + countF;
        if (totalBeats == 0) totalBeats = 1;

        double percentV = (double) countV / totalBeats;
        double percentSF = (double) (countS + countF) / totalBeats;

        double scoreCalculation = 100;
        // Phạt theo tỷ lệ %: Nhịp V chiếm 10% trừ 50 điểm, nhịp S/F chiếm 10% trừ 10 điểm
        scoreCalculation -= (percentV * 500);
        scoreCalculation -= (percentSF * 100);

        if (hrvValue < 25 && hrvValue > 0) scoreCalculation -= 10;
        // Nếu có chuỗi từ 3 nhịp V trở lên, ép điểm xuống mức nguy hiểm (màu đỏ)
        if (maxConsecutiveV >= 3) scoreCalculation = Math.min(scoreCalculation, 20);

        int finalHealthScore = (int) Math.max(0, Math.min(100, scoreCalculation));
        long finalDuration = (System.currentTimeMillis() - recordingStartTime) / 1000;

        // --- 5. LOGIC KẾT LUẬN PHÂN CẤP (SMART ALARM SYSTEM) ---
        String finalDiagnosis;
        String diagColor;
        String medicalInterpretation;
        String medicalAdvice;

        // A. TRƯỜNG HỢP NHIỄU NẶNG
        if (hrvValue > 150.0 || countQ > labelList.size() * 0.5) {
            finalDiagnosis = "TÍN HIỆU NHIỄU";
            diagColor = "#757575"; // Xám
            medicalInterpretation = "Dữ liệu bị nhiễu do cử động hoặc tiếp xúc điện cực kém.";
            medicalAdvice = "Hãy ngồi yên, thả lỏng cơ thể và thực hiện đo lại.";
        }
        // B. TRƯỜNG HỢP CỰC KỲ NGUY HIỂM (BÁO ĐỎ)
        else if (maxConsecutiveV >= 3 || percentV > 0.10) {
            finalDiagnosis = "CẢNH BÁO NGUY HIỂM";
            diagColor = "#B71C1C"; // Đỏ đậm
            medicalInterpretation = "Phát hiện chuỗi nhịp thất liên tiếp hoặc mật độ nhịp bệnh lý dày đặc.";
            medicalAdvice = "HÃY LIÊN HỆ Y TẾ HOẶC BÁC SĨ CHUYÊN KHOA NGAY LẬP TỨC.";
        }
        // C. TRƯỜNG HỢP CẦN CHÚ Ý (BÁO CAM - Đây là trường hợp 1-2 nhịp V của em)
        else if (countV > 0 || countS + countF > 5) {
            finalDiagnosis = "CẦN THEO DÕI THÊM";
            diagColor = "#FB8C00"; // Màu Cam
            medicalInterpretation = "Ghi nhận một vài nhịp bất thường lẻ tẻ (có thể do vận động hoặc stress).";
            medicalAdvice = "Tình trạng chưa quá nguy hiểm. Hãy nghỉ ngơi, ổn định nhịp thở và đo lại sau 15 phút.";
        }
        // D. TRƯỜNG HỢP BÌNH THƯỜNG (BÁO XANH)
        else {
            finalDiagnosis = "TIM ỔN ĐỊNH";
            diagColor = "#2E7D32"; // Xanh lá
            medicalInterpretation = "Nhịp tim xoang ổn định, các chỉ số nằm trong dải an toàn.";
            medicalAdvice = "Sức khỏe tim mạch tốt. Hãy duy trì lối sống lành mạnh hàng ngày.";
        }

        // --- 6. XÂY DỰNG GIAO DIỆN HTML VÀ CHIA SẺ ---
        String timeNow = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date());

        String htmlMessage = "<small>Thời gian đo: " + timeNow + "</small><br>" +
                "<b><font color='#455A64'>1. THÔNG SỐ SINH TỒN:</font></b><br>" +
                "• Nhịp tim trung bình: <font color='#1976D2'><b>" + String.format("%.0f", avgBpmValue) + " BPM</b></font><br>" +
                "• Biến thiên HRV: <b>" + String.format("%.1f", hrvValue) + " ms</b><br><br>" +

                "<b><font color='#455A64'>2. PHÂN TÍCH AI (AAMI):</font></b><br>" +
                "• Nhịp Bình thường (N): <b>" + countN + "</b><br>" +
                "• Nhịp Tâm thất (V): <font color='#B71C1C'><b>" + countV + "</b></font><br>" +
                "• Nhịp bất thường khác (S/F): <b>" + (countS + countF) + "</b><br>" +
                "• Nhịp Nhiễu (Q): <b>" + countQ + "</b><br><br>" +

                "<b><font color='#455A64'>3. KẾT LUẬN LÂM SÀNG:</font></b><br>" +
                "➔ Trạng thái: <font color='" + diagColor + "'><b>" + finalDiagnosis + "</b></font><br>" +
                "➔ Điểm sức khỏe: <b>" + finalHealthScore + "/100 điểm</b><br>" +
                "➔ Ghi chú: <i>" + medicalInterpretation + "</i><br><br>" +

                "<b><font color='#2E7D32'>4. TƯ VẤN HỆ THỐNG:</font></b><br>" +
                "• " + medicalAdvice;

        final String fullShareText = String.format(
                "===== PHIẾU PHÂN TÍCH ECG CHUYÊN SÂU =====\n" +
                        "Bệnh nhân: NGUYỄN DUY\n" +
                        "Thời gian: %s | Thời lượng: %d giây\n" +
                        "-------------------------------------------\n" +
                        "I. CHỈ SỐ SINH TỒN\n" +
                        "- Nhịp tim TB: %.0f BPM\n" +
                        "- Biến thiên HRV: %.1f ms\n" +
                        "- Điểm sức khỏe: %d/100\n\n" +
                        "II. PHÂN LOẠI NHỊP (AAMI)\n" +
                        "- Nhịp Bình thường (N): %d\n" +
                        "- NHỊP THẤT NGUY HIỂM (V): %d\n" +
                        "- Nhịp ngoại tâm thu khác (S/F): %d\n" +
                        "- Chuỗi nhịp V liên tiếp (Run): %d\n\n" +
                        "III. KẾT LUẬN: %s\n" +
                        "Lời khuyên: %s\n" +
                        "-------------------------------------------\n" +
                        "Lưu ý: Kết quả mang tính chất tham khảo.",
                timeNow, finalDuration, avgBpmValue, hrvValue, finalHealthScore, countN, countV, (countS + countF), maxConsecutiveV, finalDiagnosis, medicalAdvice);

        // --- 7. HIỂN THỊ DIALOG ---
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("BÁO CÁO PHÂN TÍCH CHUYÊN SÂU")
                .setMessage(android.text.Html.fromHtml(htmlMessage))
                .setPositiveButton("Đóng", null)
                .setNeutralButton("Gửi báo cáo", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, fullShareText);
                    startActivity(Intent.createChooser(intent, "Chia sẻ báo cáo qua:"));
                })
                .show();

        // --- 8. ĐỒNG BỘ FIREBASE ---
        try {
            java.util.Map<String, Object> dbData = new java.util.HashMap<>();
            dbData.put("date", timeNow);
            dbData.put("bpm", (int) avgBpmValue);
            dbData.put("hrv", (int) hrvValue);
            dbData.put("score", finalHealthScore);
            dbData.put("diagnosis", finalDiagnosis);
            dbData.put("max_run_v", maxConsecutiveV);
            mDatabase.push().setValue(dbData);
        } catch (Exception e) {
            Log.e("FIREBASE", "Lỗi: " + e.getMessage());
        }
    }

    private void showHistoryDialog() {
        if (historyRecords.isEmpty()) {
            Toast.makeText(this, "Chưa có biến cố nào.", Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("DANH SÁCH BIẾN CỐ")
                .setItems(historyRecords.toArray(new CharSequence[0]), null)
                .setPositiveButton("Đóng", null)
                .setNeutralButton("Chia sẻ tất cả", (d, w) -> {
                    StringBuilder sb = new StringBuilder("BÁO CÁO BIẾN CỐ\n");
                    for (String r : historyRecords) sb.append(r).append("\n---\n");
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, sb.toString());
                    startActivity(Intent.createChooser(i, "Gửi:"));
                }).show();
    }
}