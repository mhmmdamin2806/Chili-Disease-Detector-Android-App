#!/usr/bin/env python3
"""
create_dummy_model.py
=====================
Membuat model TFLite DUMMY untuk testing UI tanpa perlu dataset.
Model ini hanya memberikan output random — BUKAN untuk produksi.

Gunakan ini hanya untuk:
  - Test tampilan UI dan bounding box
  - Test koneksi model ke aplikasi
  - Test pipeline kamera

Untuk produksi: gunakan model yang dilatih dengan dataset nyata.

Jalankan:
    pip install tensorflow numpy
    python create_dummy_model.py

Output: chili_disease_model.tflite (salin ke app/src/main/assets/)
"""

import numpy as np

try:
    import tensorflow as tf
    print(f"TensorFlow {tf.__version__} ditemukan")
except ImportError:
    print("ERROR: Install TensorFlow dulu: pip install tensorflow")
    exit(1)


def create_mobilenet_v4_style_classifier(
    input_size: int = 224,
    num_classes: int = 3,
    output_path: str = "chili_disease_model.tflite"
):
    """
    Buat model klasifikasi gaya MobileNet V4.
    Input : [1, 224, 224, 3] float32
    Output: [1, 3]           float32 (probabilitas per kelas)
    """
    print(f"\n=== Membuat Model Klasifikasi (MobileNet V4 style) ===")
    print(f"Input  : [1, {input_size}, {input_size}, 3]")
    print(f"Output : [1, {num_classes}]")
    print(f"Kelas  : Sehat, Antraknosa (Patek), Layu Fusarium\n")

    # Arsitektur sederhana mirip MobileNet
    inputs = tf.keras.Input(shape=(input_size, input_size, 3), name="input")

    # Depthwise separable convolutions (gaya MobileNet)
    x = tf.keras.layers.Conv2D(32, 3, strides=2, padding='same', use_bias=False)(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU(6.)(x)

    x = tf.keras.layers.DepthwiseConv2D(3, padding='same', use_bias=False)(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU(6.)(x)
    x = tf.keras.layers.Conv2D(64, 1, use_bias=False)(x)
    x = tf.keras.layers.BatchNormalization()(x)

    x = tf.keras.layers.DepthwiseConv2D(3, strides=2, padding='same', use_bias=False)(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU(6.)(x)
    x = tf.keras.layers.Conv2D(128, 1, use_bias=False)(x)
    x = tf.keras.layers.BatchNormalization()(x)

    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    x = tf.keras.layers.Dense(num_classes, activation='softmax', name="output")(x)

    model = tf.keras.Model(inputs, x)
    model.summary()

    # Konversi ke TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) / 1024
    print(f"\n✅ Model tersimpan: {output_path} ({size_kb:.1f} KB)")
    print("⚠️  INI MODEL DUMMY - output-nya random, bukan prediksi nyata!")

    # Verifikasi
    verify_model(output_path, input_size, num_classes)
    return output_path


def create_ssd_style_detector(
    input_size: int = 320,
    num_classes: int = 3,
    max_detections: int = 10,
    output_path: str = "chili_disease_model_ssd.tflite"
):
    """
    Buat model object detection gaya SSD (4 output tensor).
    Untuk digunakan dengan mode Object Detection di ChiliDiseaseDetector.
    """
    print(f"\n=== Membuat Model SSD-Style (Object Detection) ===")
    print(f"Input   : [1, {input_size}, {input_size}, 3]")
    print(f"Output  : 4 tensor (boxes, classes, scores, count)\n")

    @tf.function(input_signature=[tf.TensorSpec([1, input_size, input_size, 3], tf.float32)])
    def detect(image):
        # Simulasi output SSD
        boxes   = tf.random.uniform([1, max_detections, 4], 0, 1)
        classes = tf.random.uniform([1, max_detections], 0, num_classes)
        scores  = tf.random.uniform([1, max_detections], 0.3, 0.95)
        count   = tf.constant([float(max_detections)])
        return boxes, classes, scores, count

    converter = tf.lite.TFLiteConverter.from_concrete_functions(
        [detect.get_concrete_function()],
        detect
    )
    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) / 1024
    print(f"✅ Model SSD tersimpan: {output_path} ({size_kb:.1f} KB)")
    return output_path


def verify_model(path: str, input_size: int, num_classes: int):
    """Verifikasi model dengan test inference."""
    print(f"\n=== Verifikasi Model ===")
    interp = tf.lite.Interpreter(model_path=path)
    interp.allocate_tensors()

    inp  = interp.get_input_details()
    outp = interp.get_output_details()

    print(f"Input  : shape={inp[0]['shape'].tolist()}, dtype={inp[0]['dtype'].__name__}")
    print(f"Output : shape={outp[0]['shape'].tolist()}, dtype={outp[0]['dtype'].__name__}")
    print(f"Jumlah output tensor: {len(outp)}")

    # Test inference
    dummy = np.random.rand(1, input_size, input_size, 3).astype(np.float32)
    interp.set_tensor(inp[0]['index'], dummy)
    interp.invoke()
    result = interp.get_tensor(outp[0]['index'])
    print(f"Output sample: {result[0]}")
    print(f"✅ Inference berhasil!")

    # Panduan pengaturan di Kotlin
    print(f"\n=== Pengaturan di Android ===")
    print(f"Jumlah output: {len(outp)}")
    if len(outp) == 1:
        print("→ Mode: KLASIFIKASI")
        print(f"→ Di ChiliDiseaseDetector.kt:")
        print(f"    MODEL_INPUT_WIDTH  = {input_size}")
        print(f"    MODEL_INPUT_HEIGHT = {input_size}")
    else:
        print("→ Mode: OBJECT DETECTION")
        print(f"→ Di ChiliDiseaseDetector.kt:")
        print(f"    MODEL_INPUT_WIDTH  = {input_size}")
        print(f"    MODEL_INPUT_HEIGHT = {input_size}")


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['classifier', 'detector'],
                        default='classifier',
                        help='classifier = MobileNet V4 style | detector = SSD style')
    parser.add_argument('--size', type=int, default=224,
                        help='Ukuran input (224 untuk classifier, 320 untuk detector)')
    parser.add_argument('--output', default='chili_disease_model.tflite')
    args = parser.parse_args()

    if args.mode == 'classifier':
        create_mobilenet_v4_style_classifier(
            input_size=args.size,
            output_path=args.output
        )
    else:
        create_ssd_style_detector(
            input_size=max(args.size, 320),
            output_path=args.output
        )

    print(f"\n📋 Langkah Selanjutnya:")
    print(f"1. Salin {args.output} ke: app/src/main/assets/chili_disease_model.tflite")
    print(f"2. Salin labels.txt ke   : app/src/main/assets/labels.txt")
    print(f"3. Sync Gradle & Build")
    print(f"\n💡 Untuk model produksi, gunakan Teachable Machine atau YOLOv8:")
    print(f"   https://teachablemachine.withgoogle.com")
