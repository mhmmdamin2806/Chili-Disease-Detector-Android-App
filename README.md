#  Chili Disease Detector Android App
Aplikasi Android real-time untuk mendeteksi penyakit tanaman cabai menggunakan kamera dan model TFLite.

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin)
![TFLite](https://img.shields.io/badge/LiteRT-1.0.1-FF6F00?logo=tensorflow)
![Min SDK](https://img.shields.io/badge/Min%20SDK-API%2024-blue)

---

## 📱 Fitur

- Deteksi real-time via kamera (CameraX)
- Mendukung **3 kelas**: Sehat, Antraknosa (Patek), Layu Fusarium
- Auto-detect model **Quantized (UINT8)** dan **Float32**
- Arsitektur MVVM + LiveData
- Riwayat deteksi + export CSV
- FPS counter real-time

---

## 🏗️ Model Flow

```
📷 CameraX (YUV_420_888)
        │
        ▼
🔄 ImageUtils.kt
   YUV → NV21 → JPEG → Bitmap → Rotate
        │
        ▼
🧠 ChiliDiseaseDetector.kt
   ┌─────────────────────────────────────┐
   │  Auto-detect: UINT8 atau FLOAT32    │
   │                                     │
   │  QUANTIZED path:                    │
   │    Input  → ByteBuffer (raw 0-255)  │
   │    Output → ByteArray               │
   │    Score  = scale × (val-zeroPoint) │
   │                                     │
   │  FLOAT32 path:                      │
   │    Input  → FloatBuffer (/255.0f)   │
   │    Output → FloatArray [0.0-1.0]    │
   └─────────────────────────────────────┘
        │
        ▼
📊 List<DetectionResult>
   boundingBox | label | confidence | classIndex
        │
        ▼
🖼️ DetectionOverlayView.kt
   Bounding box + label real-time di layar
```

---

## ⚙️ Versi yang Digunakan

| Komponen | Versi |
|---|---|
| Android Gradle Plugin (AGP) | **8.2.2** |
| Gradle Wrapper | **8.2** |
| Kotlin | **1.9.22** |
| Java / JVM Target | **17** |
| compileSdk | **34** |
| minSdk | **24** (Android 7.0) |
| targetSdk | **34** |
| CameraX | **1.3.1** |
| LiteRT (TFLite baru) | **1.0.1** |
| LiteRT Support | **1.0.1** |
| AndroidX Core KTX | **1.12.0** |
| AndroidX AppCompat | **1.6.1** |
| Material Components | **1.11.0** |
| Lifecycle ViewModel | **2.7.0** |
| Coroutines Android | **1.7.3** |

> ⚠️ Gunakan `com.google.ai.edge.litert:litert:1.0.1` (bukan `org.tensorflow:tensorflow-lite`).
> Keduanya **tidak boleh** ada bersamaan di `build.gradle` karena konflik `.so` file.

---

## 🚀 Cara Setup di Android Studio

### 1. Clone Repository

```bash
git clone https://github.com/username/ChiliDiseaseDetector.git
cd ChiliDiseaseDetector
```

### 2. Buka di Android Studio

```
File → Open → pilih folder ChiliDiseaseDetector
```

Tunggu hingga Gradle sync selesai secara otomatis.

### 3. Tambahkan Model TFLite (WAJIB)

Model **tidak disertakan** di repo karena ukuran file. Buat sendiri dengan salah satu cara:

#### Opsi A — Google Teachable Machine (5 menit, paling mudah)

1. Buka https://teachablemachine.withgoogle.com/train/image
2. Buat 3 kelas: `Sehat` · `Antraknosa (Patek)` · `Layu Fusarium` , `Lalat Buah`
3. Upload minimal 50 foto per kelas
4. Klik **Train Model**
5. Klik **Export Model** → **TensorFlow Lite** → pilih **Quantized** → **Download**
6. Rename file `converted_tflite/model.tflite` → `chili_disease_model.tflite`
7. Salin ke `app/src/main/assets

## 📁 Struktur File

```
ChiliDiseaseDetector/
├── app/
│   ├── build.gradle                   ← Dependency LiteRT, CameraX
│   └── src/main/
│       ├── AndroidManifest.xml        ← Izin CAMERA
│       ├── assets/
│       │   ├── chili_disease_model.tflite  ← ⚠️ Tambahkan sendiri
│       │   └── labels.txt             ← Daftar kelas
│       └── java/com/example/chilidisease/
│           ├── MainActivity.kt        ← UI + CameraX setup
│           ├── CameraViewModel.kt     ← MVVM state + inferensi
│           ├── detector/
│           │   ├── ChiliDiseaseDetector.kt  ← TFLite inference engine
│           │   ├── YoloV8Detector.kt        ← Parser YOLOv8 khusus
│           │   ├── DetectionResult.kt       ← Data class hasil deteksi
│           │   └── ModelConfig.kt           ← Konfigurasi model
│           ├── history/
│           │   ├── DetectionHistoryManager.kt
│           │   └── HistoryExporter.kt       ← Export CSV
│           ├── ui/
│           │   └── DetectionOverlayView.kt  ← Custom View bounding box
│           └── utils/
│               ├── ImageUtils.kt            ← YUV → Bitmap converter
│               ├── FpsCounter.kt            ← Hitung FPS real-time
│               └── BenchmarkRunner.kt       ← Benchmark inferensi
├── build.gradle                       ← Project-level (hanya plugins)
├── settings.gradle                    ← Repository declarations
├── gradle.properties
└── tools/
    ├── convert_to_tflite.py
    └── chili_disease.yaml
```

---
