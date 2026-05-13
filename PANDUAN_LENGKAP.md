# 🌶️ Chili Disease Detector — Panduan Lengkap

## Struktur File Lengkap

```
ChiliDiseaseDetector/
```

---

## Cara Menggunakan di Android Studio

### 1. Buat Project Baru
```
File → New → New Project → Empty Views Activity
Name: ChiliDiseaseDetector
Package: com.example.chilidisease
Language: Kotlin
Min SDK: API 24
```

### 2. Copy Semua File
Salin semua file sesuai struktur di atas ke dalam project Android Studio.

### 3. Tambahkan Model TFLite

**Opsi A — Google Teachable Machine (5 menit):**
1. Buka https://teachablemachine.withgoogle.com/train/image
2. Buat 3 kelas: "Sehat", "Antraknosa (Patek)", "Layu Fusarium"
3. Upload minimal 30 foto per kelas
4. Train → Export → TensorFlow Lite → Download
5. Rename jadi `chili_disease_model.tflite`
6. Copy ke `app/src/main/assets/`
7. Ubah di `ChiliDiseaseDetector.kt`:
   ```kotlin
   const val MODEL_INPUT_WIDTH = 224
   const val MODEL_INPUT_HEIGHT = 224
   ```

**Opsi B — YOLOv8 (lebih akurat, butuh dataset berlabel):**
```bash
pip install ultralytics tensorflow
python tools/convert_to_tflite.py \
    --source yolov8 \
    --train \
    --data tools/chili_disease.yaml \
    --epochs 100 \
    --imgsz 320
```
Lalu di `MainActivity.kt`, ganti penggunaan `ChiliDiseaseDetector` dengan `YoloV8Detector`.

### 4. Sync & Build
```
File → Sync Project with Gradle Files
Build → Make Project
Run → Run 'app'
```

---

## Cara Memilih Detektor yang Tepat

| Model | Kelas Detektor | `MODEL_INPUT_*` | Output Tensors |
|---|---|---|---|
| Teachable Machine | `ChiliDiseaseDetector` | 224 × 224 | 1 (klasifikasi) |
| SSD MobileNet v2 | `ChiliDiseaseDetector` | 320 × 320 | 4 (boxes+classes+scores+count) |
| EfficientDet-Lite | `ChiliDiseaseDetector` | 320 × 320 | 4 |
| YOLOv8n/s | `YoloV8Detector` | 640 × 640 | 1 ([1,7,8400]) |

---

## Penjelasan Alur Data



---

## Troubleshooting

| Masalah | Solusi |
|---|---|
| `RuntimeException: Gagal memuat model` | Cek file .tflite ada di `assets/` |
| Bounding box tidak akurat | Sesuaikan `MODEL_INPUT_WIDTH/HEIGHT` |
| FPS rendah (<5) | Set `useGpu = true` atau naikkan `MIN_INTERVAL_MS` |
| Deteksi semua kelas salah | Cek urutan nama di `labels.txt` harus sama dengan training |
| App crash saat buka kamera | Pastikan izin CAMERA ada di `AndroidManifest.xml` |
| `UnsatisfiedLinkError` TFLite | Tambahkan `aaptOptions { noCompress "tflite" }` di `build.gradle` |
