# 🛠️ Panduan Setup — Fix Error Gradle & Model

## ✅ Fix Error Gradle yang Terjadi

Error:
```
Build was configured to prefer settings repositories over project repositories
but repository 'Google' was added by build file 'build.gradle'
```

**Penyebab**: Gradle modern (8.x) menggunakan `dependencyResolutionManagement`
di `settings.gradle`, sehingga `allprojects { repositories {} }` di `build.gradle`
tidak lagi diizinkan.

**Solusi sudah diterapkan** di file ini:
- `build.gradle` (root) → hanya berisi `plugins {}` block
- `settings.gradle` → semua repository di sini
- `app/build.gradle` → Java 17, AGP 8.2.2

---

## ⚡ Langkah Setup Cepat

### 1. Extract ZIP ke folder kosong

### 2. Buka di Android Studio
```
File → Open → pilih folder ChiliDiseaseDetector
```

### 3. Tambahkan model TFLite (WAJIB)

**Opsi A — Paling Mudah: Google Teachable Machine (5 menit, gratis)**
1. Buka https://teachablemachine.withgoogle.com/train/image
2. Buat 3 kelas:
   - `Sehat` → foto cabai/daun sehat (min. 50 foto)
   - `Antraknosa (Patek)` → foto buah dengan bercak hitam (min. 50 foto)
   - `Layu Fusarium` → foto tanaman layu (min. 50 foto)
3. Klik **Train Model**
4. Klik **Export Model** → **TensorFlow Lite** → pilih **Quantized** → **Download**
5. Rename `converted_tflite/model.tflite` → `chili_disease_model.tflite`
6. Salin ke `app/src/main/assets/`
7. **TIDAK perlu ubah apapun di kode** (default sudah 224×224)

**Opsi B — Model Dummy (hanya untuk test UI)**
```bash
pip install tensorflow numpy
cd tools/
python create_dummy_model.py
# Salin output ke app/src/main/assets/chili_disease_model.tflite
```

**Opsi C — MobileNet V4 Pretrained (transfer learning)**
```python
# Install: pip install tensorflow keras
import tensorflow as tf

# Download MobileNet V4 dari TF Hub
import tensorflow_hub as hub
base = hub.KerasLayer(
    "https://tfhub.dev/google/imagenet/mobilenet_v3_small_100_224/feature_vector/5",
    input_shape=(224, 224, 3), trainable=True
)
model = tf.keras.Sequential([base, tf.keras.layers.Dense(3, activation='softmax')])
# Lanjutkan training dengan dataset cabai Anda...
# Lalu convert ke TFLite (lihat tools/convert_to_tflite.py)
```



---

**PENTING**: Urutan kelas di `labels.txt` HARUS sama dengan urutan
kelas saat training model!

---

## ⚙️ Konfigurasi Model di Kode

Buka `ChiliDiseaseDetector.kt` dan sesuaikan:

```kotlin
companion object {
    // Teachable Machine atau MobileNet V4: 224
    // SSD MobileNet atau EfficientDet:    320
    const val MODEL_INPUT_WIDTH  = 224   // ← ubah sesuai model Anda
    const val MODEL_INPUT_HEIGHT = 224   // ← ubah sesuai model Anda

    // Naikkan jika terlalu banyak false positive
    const val CONFIDENCE_THRESHOLD = 0.50f
}
```

---

## 🔧 Troubleshooting

| Error | Solusi |
|---|---|
| `Build was configured to prefer settings...` | ✅ Sudah fix — jalankan **Sync Gradle** |
| `RuntimeException: Gagal memuat model` | Tambahkan `.tflite` ke `assets/` |
| `AAPT: error: resource @style/...` | Pastikan themes.xml ada di `res/values/` |
| FPS < 5 | Naikkan `MIN_INTERVAL_MS` di `FrameAnalyzer` |
| Semua deteksi "Sehat" | Cek urutan kelas di labels.txt vs training |
| Crash `NullPointerException binding` | Pastikan semua ID di XML sesuai dengan yang dipakai di Kotlin |

---

## 📋 Versi yang Digunakan

| Komponen | Versi |
|---|---|
| Android Gradle Plugin | 8.2.2 |
| Gradle Wrapper | 8.2 |
| Kotlin | 1.9.22 |
| Java / JVM Target | 17 |
| compileSdk | 34 |
| minSdk | 24 |
| CameraX | 1.3.1 |
| TFLite | 2.14.0 |
