# Keep TensorFlow Lite GPU delegate classes referenced reflectively.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**
