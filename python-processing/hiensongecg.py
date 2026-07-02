import serial
import time
import matplotlib.pyplot as plt

# --- CẤU HÌNH ---
SERIAL_PORT = 'COM9'
BAUD_RATE = 115200
DURATION = 3 
OUTPUT_FILE = "ecg_data.csv"

def collect_ecg():
    try:
        # Mở cổng Serial với cấu hình timeout chuẩn
        ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=0.1)
        time.sleep(2) 
        
        # Xóa sạch bộ đệm cũ để tránh dữ liệu rác lúc vừa khởi động
        ser.reset_input_buffer()

        data_points = []
        print(f"--- Đang thu thập dữ liệu trong {DURATION}s... Vui lòng đợi ---")

        start_time = time.time()
        while (time.time() - start_time) < DURATION:
            if ser.in_waiting > 0:
                try:
                    line = ser.readline().decode('utf-8', errors='ignore').strip()
                    if not line: continue
                    
                    if ":" in line:
                        line = line.split(":")[-1]
                    
                    value = float(line)
                    data_points.append(value)
                except (ValueError, UnicodeDecodeError):
                    continue

        ser.close()
        
        # KIỂM TRA: Nếu không có dữ liệu thì không vẽ
        if len(data_points) == 0:
            print("Lỗi: Không nhận được dữ liệu nào từ Serial!")
            return

        print(f"--- Hoàn thành! Thu được {len(data_points)} mẫu ---")

        # 1. Lưu file
        with open(OUTPUT_FILE, "w") as f:
            for p in data_points:
                f.write(f"{p}\n")

        # 2. Vẽ đồ thị (Dùng block=True để giữ cửa sổ không bị tắt)
        print("Đang mở cửa sổ đồ thị...")
        plt.figure(figsize=(12, 6))
        plt.plot(data_points, color='red', linewidth=1)
        plt.ylim(-0.1, 1.1) 
        plt.title(f"Sóng ECG ({len(data_points)} mẫu)")
        plt.xlabel("Mẫu (Samples)")
        plt.ylabel("Giá trị")
        plt.grid(True, alpha=0.3)
        
        # Đảm bảo cửa sổ hiện lên và dừng chương trình lại cho đến khi bạn đóng nó
        plt.show(block=True) 

    except Exception as e:
        print(f"Lỗi hệ thống: {e}")

if __name__ == "__main__":
    collect_ecg()
