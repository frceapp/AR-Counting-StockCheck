# AR-Bolt: Real-Time Mechanical Parts Object Detection

AR-Bolt is an augmented reality mobile application built for Android that detects and labels real-world mechanical parts (like bolts, nuts, gears, etc.) in real time. It uses a custom-trained **YOLO** model optimized for mobile deployment via **TensorFlow Lite (TFLite)** to overlay contextual information and bounding boxes directly on the device camera feed.

## 🎯 Features

- **Real-Time Detection:** Uses a lightweight TFLite model to detect mechanical part classes (bearings, bolts, flanges, gears, nuts, retaining rings, springs, washers) with low latency.
- **On-Device Inference:** All object detection runs locally on the device using AndroidX CameraX and TensorFlow Lite.
- **AR Overlay:** Renders bounding boxes and labels anchored to detected objects on the live camera preview.

## 🏗️ Architecture

1. **Model Training (Python/Colab):** A YOLO model trained on a custom dataset, then exported to a Float16 TFLite model for mobile deployment.
2. **Mobile App (Android/Kotlin):** A native Android application that integrates CameraX to capture frames and a TFLite Interpreter to process outputs and draw bounding box overlays.

## 📁 Repository Structure

- `android/`: Native Android project source code.
- `weights/`: Contains the exported `.tflite` models (e.g., `component_parts.tflite`).
- `instruction.md`: Detailed end-to-end plan and setup instructions for model training and Android app development.
- `IDEA.md`: Initial project concept and AR features.

## 🚀 How to Run the Android App

1. **Open the Project:** Launch **Android Studio** and select **File > Open**, then navigate to the `android/` directory in this repository.
2. **Sync Gradle:** Allow Android Studio to sync and download the necessary dependencies (CameraX, TensorFlow Lite).
3. **Connect Device:** Connect your Android device via USB (ensure Developer Options and USB Debugging are enabled).
4. **Build and Run:** Click the **Run** button to install the app on your device. Point the camera at mechanical parts to test real-time detection.

## 🧠 Retraining the Model

For full details on retraining the object detection model, refer to the [instruction.md](instruction.md). The general pipeline involves:
1. Training a YOLOv8 model on a prepared dataset (e.g., via Google Colab).
2. Exporting the model to a `Float16` TFLite model.
3. Placing the newly generated `.tflite` and `labels.txt` files into `android/app/src/main/assets/`.
