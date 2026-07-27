#include <stddef.h>

/*
 * Loaded with RTLD_GLOBAL before the bundled macOS libmpv.
 *
 * The JVM code does not call into this library directly; it only needs a
 * small, valid dylib that can be packaged next to libmpv and preloaded by JNA.
 */
__attribute__((constructor))
static void mpv_kmp_macos_shim_init(void)
{
}

int mpv_kmp_macos_shim_present(void)
{
    return 1;
}

void cocoa_init_media_keys(void)
{
}

void cocoa_uninit_media_keys(void)
{
}

void cocoa_set_input_context(void *input_context)
{
    (void)input_context;
}

void cocoa_set_mpv_handle(void *ctx)
{
    (void)ctx;
}

void cocoa_init_cocoa_cb(void)
{
}

int cocoa_main(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    return 0;
}
