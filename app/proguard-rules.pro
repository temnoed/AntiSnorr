# Add project specific ProGuard rules here.
# TensorFlow Lite uses reflection/JNI, so keep its classes if minification is ever enabled.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
