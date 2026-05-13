#!/usr/bin/env python3
"""
convert_to_tflite.py
====================
Script konversi model ke format TFLite untuk digunakan di aplikasi Android.

Mendukung 3 sumber model:
  1. Keras/TensorFlow SavedModel (.pb / .h5)
  2. PyTorch YOLOv8 (via ultralytics)
  3. Model dari Google Teachable Machine (sudah TFLite, hanya copy)

Jalankan:
  pip install tensorflow ultralytics pillow numpy
  python convert_to_tflite.py --source keras --model path/to/saved_model
  python convert_to_tflite.py --source yolov8 --model yolov8n.pt --weights runs/train/weights/best.pt
  python convert_to_tflite.py --source teachable --model model.tflite
"""

import argparse
import os
import sys
import shutil
from pathlib import Path


# ================================================================
# 1. KONVERSI KERAS / TENSORFLOW SAVEDMODEL
# ================================================================

def convert_keras_to_tflite(model_path: str, output_path: str, quantize: bool = True):
    """
    Konversi model Keras/TF SavedModel ke TFLite.

    Mendukung:
    - model.h5 (Keras)
    - saved_model/ direktori (TF SavedModel)
    - model.pb (frozen graph)
    """
    import tensorflow as tf

    print(f"📦 Memuat model dari: {model_path}")

    # Deteksi format model
    if model_path.endswith('.h5') or model_path.endswith('.keras'):
        model = tf.keras.models.load_model(model_path)
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
    else:
        # SavedModel directory atau .pb
        converter = tf.lite.TFLiteConverter.from_saved_model(model_path)

    # Optimasi default (mengurangi ukuran, meningkatkan kecepatan)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    if quantize:
        # Float16 quantization: 2x lebih kecil, hampir sama akurat
        converter.target_spec.supported_types = [tf.float16]
        print("⚙️  Quantization: Float16")

    print("🔄 Mengkonversi...")
    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_mb = len(tflite_model) / (1024 * 1024)
    print(f"✅ Tersimpan: {output_path} ({size_mb:.2f} MB)")

    # Verifikasi model
    verify_tflite(output_path)


# ================================================================
# 2. KONVERSI YOLOV8 (ultralytics)
# ================================================================

def convert_yolov8_to_tflite(
    base_model: str,
    weights_path: str,
    output_path: str,
    imgsz: int = 320
):
    """
    Fine-tune dan konversi YOLOv8 ke TFLite.

    Parameters:
        base_model: 'yolov8n.pt', 'yolov8s.pt', dll
        weights_path: Path ke weights hasil training (best.pt)
        output_path: Path output .tflite
        imgsz: Ukuran gambar input (320 atau 640)
    """
    from ultralytics import YOLO

    print(f"📦 Memuat YOLOv8: {weights_path}")
    model = YOLO(weights_path)

    print(f"🔄 Mengekspor ke TFLite (imgsz={imgsz})...")
    export_path = model.export(
        format='tflite',
        imgsz=imgsz,
        optimize=True,
        half=False,  # True untuk FP16, False untuk FP32
    )

    # Salin ke output path
    shutil.copy(export_path, output_path)
    print(f"✅ Tersimpan: {output_path}")
    print(f"ℹ️  Di kelas ChiliDiseaseDetector, gunakan YoloV8Detector.kt")
    print(f"   Sesuaikan MODEL_INPUT_WIDTH/HEIGHT = {imgsz}")


def train_yolov8(
    data_yaml: str = 'chili_disease.yaml',
    base_model: str = 'yolov8n.pt',
    epochs: int = 100,
    imgsz: int = 320
):
    """
    Training YOLOv8 dari scratch atau fine-tune.

    Contoh data.yaml:
    ```yaml
    path: /path/to/dataset
    train: images/train
    val: images/val
    nc: 3
    names: ['Sehat', 'Antraknosa (Patek)', 'Layu Fusarium']
    ```
    """
    from ultralytics import YOLO

    print(f"🚀 Mulai training YOLOv8...")
    print(f"   Model: {base_model}")
    print(f"   Data:  {data_yaml}")
    print(f"   Epochs:{epochs}")
    print(f"   ImgSz: {imgsz}")

    model = YOLO(base_model)
    results = model.train(
        data=data_yaml,
        epochs=epochs,
        imgsz=imgsz,
        batch=16,
        name='chili_disease_detector',
        patience=20,            # Early stopping
        save=True,
        plots=True,
        verbose=True
    )

    print(f"\n✅ Training selesai!")
    print(f"   Best weights: runs/detect/chili_disease_detector/weights/best.pt")
    return results


# ================================================================
# 3. VALIDASI MODEL TFLITE
# ================================================================

def verify_tflite(model_path: str):
    """
    Verifikasi model TFLite dengan test inference sederhana.
    """
    import numpy as np
    import tensorflow as tf

    print(f"\n🔍 Verifikasi model: {model_path}")

    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print(f"   Input tensors: {len(input_details)}")
    for i, inp in enumerate(input_details):
        print(f"     [{i}] shape={inp['shape'].tolist()}, dtype={inp['dtype'].__name__}")

    print(f"   Output tensors: {len(output_details)}")
    for i, out in enumerate(output_details):
        print(f"     [{i}] shape={out['shape'].tolist()}, dtype={out['dtype'].__name__}")

    # Test inference dengan gambar random
    input_shape = input_details[0]['shape']
    dummy_input = np.random.rand(*input_shape).astype(np.float32)
    interpreter.set_tensor(input_details[0]['index'], dummy_input)
    interpreter.invoke()

    print(f"   ✅ Test inference berhasil!")

    # Informasi untuk developer
    num_outputs = len(output_details)
    if num_outputs >= 4:
        print(f"   ℹ️  Mode: Object Detection (gunakan ChiliDiseaseDetector)")
    elif num_outputs == 1:
        out_shape = output_details[0]['shape']
        if len(out_shape) == 3:
            print(f"   ℹ️  Mode: YOLOv8 (gunakan YoloV8Detector)")
        else:
            print(f"   ℹ️  Mode: Klasifikasi (gunakan ChiliDiseaseDetector)")

    return interpreter


# ================================================================
# 4. BUAT DATASET YAML UNTUK YOLOV8
# ================================================================

def create_dataset_yaml(
    dataset_path: str,
    output_yaml: str = 'chili_disease.yaml'
):
    """Buat file konfigurasi dataset YOLOv8"""
    yaml_content = f"""# Dataset Penyakit Cabai untuk YOLOv8
# Struktur folder:
# {dataset_path}/
# ├── images/
# │   ├── train/   (80% gambar)
# │   └── val/     (20% gambar)
# └── labels/
#     ├── train/   (file .txt per gambar)
#     └── val/

path: {os.path.abspath(dataset_path)}
train: images/train
val: images/val

# Jumlah kelas
nc: 3

# Nama kelas (urutan HARUS sama dengan labels.txt di Android)
names:
  0: Sehat
  1: Antraknosa (Patek)
  2: Layu Fusarium
"""
    with open(output_yaml, 'w', encoding='utf-8') as f:
        f.write(yaml_content)
    print(f"✅ Dataset YAML tersimpan: {output_yaml}")


# ================================================================
# 5. MAIN CLI
# ================================================================

def main():
    parser = argparse.ArgumentParser(
        description='Konversi model ke TFLite untuk aplikasi Deteksi Penyakit Cabai'
    )
    parser.add_argument('--source', choices=['keras', 'yolov8', 'teachable', 'verify'],
                        required=True, help='Sumber model')
    parser.add_argument('--model', type=str, help='Path model input')
    parser.add_argument('--weights', type=str, help='Path weights YOLOv8 (best.pt)')
    parser.add_argument('--output', type=str, default='chili_disease_model.tflite')
    parser.add_argument('--imgsz', type=int, default=320, help='Ukuran gambar (YOLOv8)')
    parser.add_argument('--no-quantize', action='store_true', help='Nonaktifkan quantization')
    parser.add_argument('--train', action='store_true', help='Jalankan training YOLOv8')
    parser.add_argument('--data', type=str, default='chili_disease.yaml')
    parser.add_argument('--epochs', type=int, default=100)

    args = parser.parse_args()

    if args.source == 'keras':
        convert_keras_to_tflite(args.model, args.output, not args.no_quantize)

    elif args.source == 'yolov8':
        if args.train:
            train_yolov8(args.data, args.model or 'yolov8n.pt', args.epochs, args.imgsz)
        weights = args.weights or 'runs/detect/chili_disease_detector/weights/best.pt'
        convert_yolov8_to_tflite(args.model or 'yolov8n.pt', weights, args.output, args.imgsz)

    elif args.source == 'teachable':
        # Teachable Machine sudah TFLite, tinggal rename/copy
        shutil.copy(args.model, args.output)
        print(f"✅ File disalin ke: {args.output}")
        print(f"ℹ️  Ubah MODEL_INPUT_WIDTH/HEIGHT = 224 di ChiliDiseaseDetector.kt")
        verify_tflite(args.output)

    elif args.source == 'verify':
        verify_tflite(args.model)

    print(f"\n📱 Langkah selanjutnya:")
    print(f"   1. Salin {args.output} ke: app/src/main/assets/")
    print(f"   2. Pastikan labels.txt berisi:")
    print(f"      Sehat")
    print(f"      Antraknosa (Patek)")
    print(f"      Layu Fusarium")
    print(f"   3. Build & run aplikasi Android")


if __name__ == '__main__':
    main()
