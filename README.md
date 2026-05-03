# Phone-In-Hand-Detector

Android Studio project for TMM homework 26, variant 25: real-time detection and tracking of a mobile phone in a person's hand.

## Stack

- Kotlin
- CameraX
- TensorFlow Lite Task Vision
- EfficientDet-Lite0 model trained on COCO classes

## Run

1. Open this folder in Android Studio.
2. Wait for Gradle Sync to finish.
3. Connect an Android phone with USB debugging enabled.
4. Run the `app` configuration.

The app shows the camera stream, draws bounding boxes around detected phones and people, and displays a smoothed state: no phone, phone detected, or phone in hand.
