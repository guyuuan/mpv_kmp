# The Android bridge exports name-based JNI entry points for this object.
-keep,allowoptimization class com.guyuuan.kmp.mpv.MpvNative {
    native <methods>;
}

# mpv_jni.cpp creates this DTO with FindClass/GetMethodID.
-keep,allowoptimization class com.guyuuan.kmp.mpv.MpvEventDTO {
    public <init>(int, int, long, java.lang.String, java.lang.String);
}
