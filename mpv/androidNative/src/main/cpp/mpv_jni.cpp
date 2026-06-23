#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <android/log.h>

#define LOG_TAG "mpv_kmp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void* mpv_handle_ptr = nullptr;
static void* lib_handle = nullptr;
static void* avcodec_handle = nullptr;
static JavaVM* java_vm_ptr = nullptr;
static jobject surface_ref = nullptr;

typedef enum mpv_format {
    MPV_FORMAT_NONE             = 0,
    MPV_FORMAT_STRING           = 1,
    MPV_FORMAT_INT64            = 4
} mpv_format;

typedef enum mpv_render_param_type {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_ANDROID_NATIVE_WINDOW = 9
} mpv_render_param_type;

typedef struct mpv_render_param {
    mpv_render_param_type type;
    void *data;
} mpv_render_param;

typedef struct mpv_render_context mpv_render_context;

typedef struct mpv_event_property {
    const char *name;
    mpv_format format;
    void *data;
} mpv_event_property;

typedef struct mpv_event {
    int event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;

typedef struct mpv_event_log_message {
    const char *prefix;
    const char *level;
    const char *text;
    int log_level;
} mpv_event_log_message;

typedef void* (*fn_mpv_create)();
typedef int (*fn_mpv_initialize)(void*);
typedef const char* (*fn_mpv_error_string)(int);
typedef int (*fn_mpv_request_log_messages)(void*, const char*);
typedef int (*fn_mpv_command_string)(void*, const char*);
typedef int (*fn_mpv_set_option_string)(void*, const char*, const char*);
typedef int (*fn_mpv_set_property_string)(void*, const char*, const char*);
typedef int (*fn_mpv_set_property)(void*, const char*, mpv_format, void*);
typedef int (*fn_mpv_get_property_string)(void*, const char*, char**);
typedef int (*fn_mpv_observe_property)(void*, uint64_t, const char*, int);
typedef mpv_event* (*fn_mpv_wait_event)(void*, double);
typedef void (*fn_mpv_wakeup)(void*);
typedef void (*fn_mpv_free)(void*);
typedef void (*fn_mpv_terminate_destroy)(void*);
typedef int (*fn_mpv_render_context_create)(mpv_render_context**, void*, mpv_render_param*);
typedef void (*fn_mpv_render_context_free)(mpv_render_context*);
typedef int (*fn_mpv_render_context_render)(mpv_render_context*, mpv_render_param*);
typedef void (*fn_mpv_render_context_set_update_callback)(mpv_render_context*, void (*)(void*), void*);
typedef void (*fn_mpv_render_context_report_swap)(mpv_render_context*);
typedef int (*fn_av_jni_set_java_vm)(void*, void*);

static fn_mpv_create p_mpv_create = nullptr;
static fn_mpv_initialize p_mpv_initialize = nullptr;
static fn_mpv_error_string p_mpv_error_string = nullptr;
static fn_mpv_request_log_messages p_mpv_request_log_messages = nullptr;
static fn_mpv_command_string p_mpv_command_string = nullptr;
static fn_mpv_set_option_string p_mpv_set_option_string = nullptr;
static fn_mpv_set_property_string p_mpv_set_property_string = nullptr;
static fn_mpv_set_property p_mpv_set_property = nullptr;
static fn_mpv_get_property_string p_mpv_get_property_string = nullptr;
static fn_mpv_observe_property p_mpv_observe_property = nullptr;
static fn_mpv_wait_event p_mpv_wait_event = nullptr;
static fn_mpv_wakeup p_mpv_wakeup = nullptr;
static fn_mpv_free p_mpv_free = nullptr;
static fn_mpv_terminate_destroy p_mpv_terminate_destroy = nullptr;
static fn_mpv_render_context_create p_mpv_render_context_create = nullptr;
static fn_mpv_render_context_free p_mpv_render_context_free = nullptr;
static fn_mpv_render_context_render p_mpv_render_context_render = nullptr;
static fn_mpv_render_context_set_update_callback p_mpv_render_context_set_update_callback = nullptr;
static fn_mpv_render_context_report_swap p_mpv_render_context_report_swap = nullptr;
static fn_av_jni_set_java_vm p_av_jni_set_java_vm = nullptr;

static const char* mpv_error(int error) {
    return p_mpv_error_string ? p_mpv_error_string(error) : "unknown";
}

static void register_java_vm() {
    if (!java_vm_ptr) return;
    if (!avcodec_handle) {
        avcodec_handle = dlopen("libavcodec.so", RTLD_NOW);
        if (!avcodec_handle) {
            LOGE("dlopen libavcodec.so failed: %s", dlerror());
            return;
        }
    }
    if (!p_av_jni_set_java_vm) {
        p_av_jni_set_java_vm = (fn_av_jni_set_java_vm)dlsym(avcodec_handle, "av_jni_set_java_vm");
        if (!p_av_jni_set_java_vm) {
            LOGE("dlsym av_jni_set_java_vm failed: %s", dlerror());
            return;
        }
    }
    int r = p_av_jni_set_java_vm(java_vm_ptr, nullptr);
    if (r < 0) {
        LOGE("av_jni_set_java_vm failed: %d", r);
    }
}

static void resolve() {
    if (lib_handle) return;
    register_java_vm();
    lib_handle = dlopen("libmpv.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib_handle) {
        LOGE("dlopen libmpv.so failed: %s", dlerror());
        return;
    }
    p_mpv_create = (fn_mpv_create)dlsym(lib_handle, "mpv_create");
    p_mpv_initialize = (fn_mpv_initialize)dlsym(lib_handle, "mpv_initialize");
    p_mpv_error_string = (fn_mpv_error_string)dlsym(lib_handle, "mpv_error_string");
    p_mpv_request_log_messages = (fn_mpv_request_log_messages)dlsym(lib_handle, "mpv_request_log_messages");
    p_mpv_command_string = (fn_mpv_command_string)dlsym(lib_handle, "mpv_command_string");
    p_mpv_set_option_string = (fn_mpv_set_option_string)dlsym(lib_handle, "mpv_set_option_string");
    p_mpv_set_property_string = (fn_mpv_set_property_string)dlsym(lib_handle, "mpv_set_property_string");
    p_mpv_set_property = (fn_mpv_set_property)dlsym(lib_handle, "mpv_set_property");
    p_mpv_get_property_string = (fn_mpv_get_property_string)dlsym(lib_handle, "mpv_get_property_string");
    p_mpv_observe_property = (fn_mpv_observe_property)dlsym(lib_handle, "mpv_observe_property");
    p_mpv_wait_event = (fn_mpv_wait_event)dlsym(lib_handle, "mpv_wait_event");
    p_mpv_wakeup = (fn_mpv_wakeup)dlsym(lib_handle, "mpv_wakeup");
    p_mpv_free = (fn_mpv_free)dlsym(lib_handle, "mpv_free");
    p_mpv_terminate_destroy = (fn_mpv_terminate_destroy)dlsym(lib_handle, "mpv_terminate_destroy");
    p_mpv_render_context_create = (fn_mpv_render_context_create)dlsym(lib_handle, "mpv_render_context_create");
    p_mpv_render_context_free = (fn_mpv_render_context_free)dlsym(lib_handle, "mpv_render_context_free");
    p_mpv_render_context_render = (fn_mpv_render_context_render)dlsym(lib_handle, "mpv_render_context_render");
    p_mpv_render_context_set_update_callback = (fn_mpv_render_context_set_update_callback)dlsym(lib_handle, "mpv_render_context_set_update_callback");
    p_mpv_render_context_report_swap = (fn_mpv_render_context_report_swap)dlsym(lib_handle, "mpv_render_context_report_swap");
    if (!p_mpv_create || !p_mpv_initialize || !p_mpv_command_string ||
        !p_mpv_set_option_string || !p_mpv_set_property_string ||
        !p_mpv_set_property || !p_mpv_wait_event || !p_mpv_terminate_destroy) {
        LOGE("failed to resolve required libmpv symbols");
    }
    register_java_vm();
}

static bool set_option(const char* name, const char* value) {
    int r = p_mpv_set_option_string(mpv_handle_ptr, name, value);
    if (r < 0) {
        LOGE("mpv_set_option_string %s=%s failed: %d (%s)", name, value, r, mpv_error(r));
        return false;
    }
    return true;
}

static void set_surface_wid() {
    if (!mpv_handle_ptr || !p_mpv_set_property || !surface_ref) return;
    int64_t wid = reinterpret_cast<intptr_t>(surface_ref);
    int r = p_mpv_set_property(mpv_handle_ptr, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) {
        LOGE("mpv_set_property wid failed: %d (%s)", r, mpv_error(r));
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    java_vm_ptr = vm;
    register_java_vm();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvInit(JNIEnv*, jclass) {
    resolve();
    if (!p_mpv_create || !p_mpv_initialize || !p_mpv_set_option_string ||
        !p_mpv_terminate_destroy) {
        LOGE("mpvInit failed: required symbols are missing");
        return JNI_FALSE;
    }
    if (mpv_handle_ptr) return JNI_TRUE;
    mpv_handle_ptr = p_mpv_create();
    if (!mpv_handle_ptr) {
        LOGE("mpv_create failed");
        return JNI_FALSE;
    }
    if (!set_option("vo", "gpu") ||
        !set_option("gpu-context", "android") ||
        !set_option("gpu-api", "opengl") ||
        !set_option("ao", "audiotrack")) {
        p_mpv_terminate_destroy(mpv_handle_ptr);
        mpv_handle_ptr = nullptr;
        return JNI_FALSE;
    }
    int r = p_mpv_initialize(mpv_handle_ptr);
    if (r < 0) {
        LOGE("mpv_initialize failed: %d (%s)", r, mpv_error(r));
        p_mpv_terminate_destroy(mpv_handle_ptr);
        mpv_handle_ptr = nullptr;
        return JNI_FALSE;
    }
    if (p_mpv_request_log_messages) {
        int log_result = p_mpv_request_log_messages(mpv_handle_ptr, "warn");
        if (log_result < 0) {
            LOGW("mpv_request_log_messages failed: %d (%s)", log_result, mpv_error(log_result));
        }
    }
    set_surface_wid();
    LOGI("mpv initialized");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvAttachSurface(JNIEnv* env, jclass, jobject surface) {
    if (surface_ref) {
        env->DeleteGlobalRef(surface_ref);
        surface_ref = nullptr;
    }
    if (surface) {
        surface_ref = env->NewGlobalRef(surface);
        set_surface_wid();
    } else {
        if (mpv_handle_ptr && p_mpv_set_property_string) {
            int r = p_mpv_set_property_string(mpv_handle_ptr, "wid", "0");
            if (r < 0) {
                LOGE("mpv_set_property_string wid=0 failed: %d (%s)", r, mpv_error(r));
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvDetachSurface(JNIEnv* env, jclass) {
    if (mpv_handle_ptr && p_mpv_set_property_string) {
        int r = p_mpv_set_property_string(mpv_handle_ptr, "wid", "0");
        if (r < 0) {
            LOGE("mpv_set_property_string wid=0 failed: %d (%s)", r, mpv_error(r));
        }
    }
    if (surface_ref) {
        env->DeleteGlobalRef(surface_ref);
        surface_ref = nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvCommandString(JNIEnv* env, jclass, jstring cmd) {
    if (!p_mpv_command_string || !mpv_handle_ptr) {
        LOGE("mpvCommandString called before mpv was initialized");
        return -1;
    }
    const char* s = env->GetStringUTFChars(cmd, nullptr);
    int r = p_mpv_command_string(mpv_handle_ptr, s);
    if (r < 0) {
        LOGE("mpv_command_string failed: %d (%s), cmd=%s", r, mpv_error(r), s);
    }
    env->ReleaseStringUTFChars(cmd, s);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvSetProperty(JNIEnv* env, jclass, jstring name, jstring value) {
    if (!p_mpv_set_property_string || !mpv_handle_ptr) {
        LOGE("mpvSetProperty called before mpv was initialized");
        return -1;
    }
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    int r = p_mpv_set_property_string(mpv_handle_ptr, n, v);
    if (r < 0) {
        LOGE("mpv_set_property_string failed: %d (%s), %s=%s", r, mpv_error(r), n, v);
    }
    env->ReleaseStringUTFChars(name, n);
    env->ReleaseStringUTFChars(value, v);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvGetProperty(JNIEnv* env, jclass, jstring name) {
    if (!p_mpv_get_property_string || !mpv_handle_ptr || !p_mpv_free) return nullptr;
    const char* n = env->GetStringUTFChars(name, nullptr);
    char* out = nullptr;
    int r = p_mpv_get_property_string(mpv_handle_ptr, n, &out);
    env->ReleaseStringUTFChars(name, n);
    if (r < 0 || !out) return nullptr;
    jstring js = env->NewStringUTF(out);
    p_mpv_free(out);
    return js;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvObserveProperty(JNIEnv* env, jclass, jstring name, jint format) {
    if (!p_mpv_observe_property || !mpv_handle_ptr) {
        LOGE("mpvObserveProperty called before mpv was initialized");
        return -1;
    }
    const char* n = env->GetStringUTFChars(name, nullptr);
    int r = p_mpv_observe_property(mpv_handle_ptr, 0, n, format);
    if (r < 0) {
        LOGE("mpv_observe_property failed: %d (%s), name=%s", r, mpv_error(r), n);
    }
    env->ReleaseStringUTFChars(name, n);
    return r;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvWaitEvent(JNIEnv* env, jclass, jdouble timeout) {
    if (!p_mpv_wait_event || !mpv_handle_ptr) return nullptr;
    mpv_event* e = p_mpv_wait_event(mpv_handle_ptr, timeout);
    if (!e || e->event_id == 0) return nullptr;

    jclass dtoCls = env->FindClass("com/guyuuan/mpv_kmp/MpvEventDTO");
    jmethodID ctor = env->GetMethodID(dtoCls, "<init>", "(IILjava/lang/String;Ljava/lang/String;)V");

    jstring jName = nullptr;
    jstring jValue = nullptr;

    if (e->event_id == 22 && e->data) { // MPV_EVENT_PROPERTY_CHANGE = 22
        mpv_event_property* prop = (mpv_event_property*)e->data;
        if (prop->name) jName = env->NewStringUTF(prop->name);
        if (prop->format == MPV_FORMAT_STRING && prop->data) {
             const char* const* str = (const char* const*)prop->data;
             if (str && *str) jValue = env->NewStringUTF(*str);
        }
    } else if (e->event_id == 2 && e->data) { // MPV_EVENT_LOG_MESSAGE = 2
        mpv_event_log_message* msg = (mpv_event_log_message*)e->data;
        if (msg->prefix) jName = env->NewStringUTF(msg->prefix);
        if (msg->text) jValue = env->NewStringUTF(msg->text);
        if (msg->level && msg->prefix && msg->text) {
            LOGI("mpv[%s] %s: %s", msg->level, msg->prefix, msg->text);
        }
    }

    return env->NewObject(dtoCls, ctor, (jint)e->event_id, (jint)e->error, jName, jValue);
}

extern "C" JNIEXPORT void JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvWakeup(JNIEnv*, jclass) {
    if (p_mpv_wakeup && mpv_handle_ptr) {
        p_mpv_wakeup(mpv_handle_ptr);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvTerminate(JNIEnv* env, jclass) {
    if (p_mpv_terminate_destroy && mpv_handle_ptr) {
        if (p_mpv_wakeup) {
            p_mpv_wakeup(mpv_handle_ptr); // Wake up wait loop
        }
        p_mpv_terminate_destroy(mpv_handle_ptr);
        mpv_handle_ptr = nullptr;
    }
    if (surface_ref) {
        env->DeleteGlobalRef(surface_ref);
        surface_ref = nullptr;
    }
}
