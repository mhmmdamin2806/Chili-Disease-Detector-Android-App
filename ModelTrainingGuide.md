# 🧠 Panduan Melatih Model TFLite untuk Deteksi Penyakit Cabai

## Opsi 1: Google Teachable Machine (Paling Mudah)

### Langkah-langkah:
1. Buka https://teachablemachine.withgoogle.com
2. Pilih **"Image Project"** → **"Standard Image Model"**
3. Buat 3 kelas:
   - **Kelas 1**: "Sehat" → Upload 50-200 foto cabai sehat
   - **Kelas 2**: "Antraknosa (Patek)" → Upload 50-200 foto cabai dengan bercak hitam
   - **Kelas 3**: "Layu Fusarium" → Upload 50-200 foto dengan gejala layu
4. Klik **"Train Model"**
5. Klik **"Export Model"** → **"TensorFlow Lite"** → Download

### Hasil:
- File: `converted_tflite/model.tflite`
- Rename menjadi: `chili_disease_model.tflite`
- Copy ke: `app/src/main/assets/`
- **Catatan**: Model Teachable Machine = Klasifikasi (1 output tensor)

---

## Opsi 2: TensorFlow Object Detection API (Lebih Akurat)

### Persiapan Dataset:
```
dataset/
├── images/
│   ├── train/      # 80% gambar
│   └── val/        # 20% gambar
└── annotations/
    ├── train/      # File XML label (PASCAL VOC format)
    └── val/
```

### Format Anotasi (PASCAL VOC):
```xml
<annotation>
  <filename>cabai_001.jpg</filename>
  <size>
    <width>640</width>
    <height>640</height>
  </size>
  <object>
    <name>Antraknosa (Patek)</name>
    <bndbox>
      <xmin>120</xmin>
      <ymin>80</ymin>
      <xmax>280</xmax>
      <ymax>200</ymax>
    </bndbox>
  </object>
</annotation>
```

### Training dengan Python:
```python
# Install dependencies
pip install tensorflow tensorflow-object-detection-api

# Download pre-trained model (SSD MobileNet V2 FPNLite 320x320)
# dari: https://tfhub.dev/tensorflow/ssd_mobilenet_v2/fpnlite_320x320/1

# Fine-tune dengan dataset lokal
python model_main_tf2.py \
    --model_dir=models/chili_detector \
    --pipeline_config_path=configs/ssd_mobilenet_v2_320x320.config \
    --num_train_steps=50000
```

### Konversi ke TFLite:
```python
import tensorflow as tf

# Export SavedModel
saved_model_dir = "models/chili_detector/saved_model"

# Konversi ke TFLite
converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
converter.optimizations = [tf.lite.Optimize.DEFAULT]

# Quantization untuk performa lebih baik di mobile
# converter.target_spec.supported_types = [tf.float16]  # Opsional

tflite_model = converter.convert()

# Simpan
with open("chili_disease_model.tflite", "wb") as f:
    f.write(tflite_model)

print("✅ Model berhasil dikonversi!")
```

---

## Opsi 3: Roboflow + YOLOv8 (Direkomendasikan)

### Langkah:
1. Anotasi gambar di https://roboflow.com
2. Export dalam format YOLOv8
3. Training:
```python
from ultralytics import YOLO

# Load model pre-trained
model = YOLO("yolov8n.pt")

# Training
model.train(
    data="chili_disease.yaml",
    epochs=100,
    imgsz=320,
    batch=16,
    name="chili_detector"
)

# Export ke TFLite
model.export(format="tflite", imgsz=320)
```

---

## Penempatan File Model

```
app/src/main/assets/
├── chili_disease_model.tflite   ← Model utama
└── labels.txt                   ← Daftar nama kelas
```

## Format labels.txt
```
Sehat
Antraknosa (Patek)
Layu Fusarium
```
*(Satu label per baris, urutan harus sesuai dengan output model)*

---

## Menyesuaikan Kode dengan Model Anda

### Jika menggunakan Teachable Machine (Klasifikasi):
```kotlin
// Di ChiliDiseaseDetector.kt
const val MODEL_INPUT_WIDTH = 224   // Teachable Machine default
const val MODEL_INPUT_HEIGHT = 224
```
Model ini otomatis terdeteksi sebagai klasifikasi (1 output tensor).

### Jika menggunakan SSD MobileNet (Object Detection):
```kotlin
// Di ChiliDiseaseDetector.kt
const val MODEL_INPUT_WIDTH = 320
const val MODEL_INPUT_HEIGHT = 320
```
Model ini otomatis terdeteksi sebagai object detection (4 output tensor).

### Jika menggunakan YOLOv8 TFLite:
```kotlin
// Override runInference() di ChiliDiseaseDetector.kt
// YOLOv8 memiliki format output berbeda: [1, 8400, num_classes+4]
// Perlu parsing khusus
```

---

## Sumber Dataset Publik

- **Kaggle**: cari "chili disease dataset"
- **Roboflow Universe**: https://universe.roboflow.com (cari "chili pepper disease")
- **PlantVillage Dataset**: Dataset penyakit tanaman publik
- **iPlant**: Dataset tanaman tropis Asia Tenggara
