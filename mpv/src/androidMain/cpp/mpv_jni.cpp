#include <jni.h>
#include <dlfcn.h>
#include <cstring>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string>

static void* mpv_handle_ptr = nullptr;
static void* lib_handle = nullptr;
static ANativeWindow* native_window = nullptr;

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

typedef void* (*fn_mpv_create)();
typedef int (*fn_mpv_initialize)(void*);
typedef int (*fn_mpv_command_string)(void*, const char*);
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

static fn_mpv_create p_mpv_create = nullptr;
static fn_mpv_initialize p_mpv_initialize = nullptr;
static fn_mpv_command_string p_mpv_command_string = nullptr;
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

static void resolve() {
    if (lib_handle) return;
    lib_handle = dlopen("libmpv.so", RTLD_LAZY);
    if (!lib_handle) return;
    p_mpv_create = (fn_mpv_create)dlsym(lib_handle, "mpv_create");
    p_mpv_initialize = (fn_mpv_initialize)dlsym(lib_handle, "mpv_initialize");
    p_mpv_command_string = (fn_mpv_command_string)dlsym(lib_handle, "mpv_command_string");
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
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvInit(JNIEnv*, jclass) {
    resolve();
    if (!p_mpv_create || !p_mpv_initialize) return JNI_FALSE;
    if (mpv_handle_ptr) return JNI_TRUE;
    mpv_handle_ptr = p_mpv_create();
    if (!mpv_handle_ptr) return JNI_FALSE;
    int r = p_mpv_initialize(mpv_handle_ptr);
    return r == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvAttachSurface(JNIEnv* env, jclass, jobject surface) {
    if (!mpv_handle_ptr) return;
    
    // Release previous if any
    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = nullptr;
    }
    
    if (surface) {
        native_window = ANativeWindow_fromSurface(env, surface);
        if (native_window && p_mpv_set_property) {
            int64_t wid = (int64_t)native_window;
            p_mpv_set_property(mpv_handle_ptr, "wid", MPV_FORMAT_INT64, &wid);
        }
    } else {
        if (p_mpv_set_property_string) {
            p_mpv_set_property_string(mpv_handle_ptr, "wid", "0");
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvDetachSurface(JNIEnv*, jclass) {
    if (!mpv_handle_ptr) return;
    if (p_mpv_set_property_string) {
        p_mpv_set_property_string(mpv_handle_ptr, "wid", "0");
    }
    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvCommandString(JNIEnv* env, jclass, jstring cmd) {
    if (!p_mpv_command_string || !mpv_handle_ptr) return -1;
    const char* s = env->GetStringUTFChars(cmd, nullptr);
    int r = p_mpv_command_string(mpv_handle_ptr, s);
    env->ReleaseStringUTFChars(cmd, s);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvSetProperty(JNIEnv* env, jclass, jstring name, jstring value) {
    if (!p_mpv_set_property_string || !mpv_handle_ptr) return -1;
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    int r = p_mpv_set_property_string(mpv_handle_ptr, n, v);
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
    if (!p_mpv_observe_property || !mpv_handle_ptr) return -1;
    const char* n = env->GetStringUTFChars(name, nullptr);
    int r = p_mpv_observe_property(mpv_handle_ptr, 0, n, format);
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
             char** strPtr = (char**)prop->data;
             if (*strPtr) jValue = env->NewStringUTF(*strPtr);
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
Java_com_guyuuan_mpv_1kmp_MpvNative_mpvTerminate(JNIEnv*, jclass) {
    if (p_mpv_terminate_destroy && mpv_handle_ptr) {
        p_mpv_wakeup(mpv_handle_ptr); // Wake up wait loop
        p_mpv_terminate_destroy(mpv_handle_ptr);
        mpv_handle_ptr = nullptr;
    }
}
