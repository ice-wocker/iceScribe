# JNI：native 层按「字符串名字」查找的 Java 符号绝不能改名，
# 否则 release 包（R8 之后）会在运行时抛 NoSuchMethodError（踩过这个坑）。
-keepclasseswithmembers class com.ice.scribe.stt.WhisperBridge {
    native <methods>;
}
-keep class com.ice.scribe.stt.WhisperBridge { *; }
-keep class com.ice.scribe.stt.TranscribeCallback { *; }
-keepclasseswithmembers class * implements com.ice.scribe.stt.TranscribeCallback {
    public void on*;
}