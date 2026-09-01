#ifndef MPV_KMP_ANDROID_MPV_BRIDGE_H
#define MPV_KMP_ANDROID_MPV_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int32_t mpv_bridge_on_load(void* vm, void* reserved);
uint8_t mpv_bridge_init(void* env, void* clazz);
uint8_t mpv_bridge_create(void* env, void* clazz);
int32_t mpv_bridge_set_option(void* env, void* clazz, void* name, void* value);
uint8_t mpv_bridge_initialize(void* env, void* clazz, void* log_level);
void mpv_bridge_attach_surface(void* env, void* clazz, void* surface);
void mpv_bridge_detach_surface(void* env, void* clazz);
int32_t mpv_bridge_command_string(void* env, void* clazz, void* command);
int32_t mpv_bridge_set_property(void* env, void* clazz, void* name, void* value);
void* mpv_bridge_get_property(void* env, void* clazz, void* name);
int32_t mpv_bridge_observe_property(
    void* env,
    void* clazz,
    void* name,
    int64_t reply_userdata,
    int32_t format
);
int32_t mpv_bridge_unobserve_property(void* env, void* clazz, int64_t reply_userdata);
void* mpv_bridge_wait_event(void* env, void* clazz, double timeout);
void mpv_bridge_wakeup(void* env, void* clazz);
void mpv_bridge_terminate(void* env, void* clazz);

#ifdef __cplusplus
}
#endif

#endif
