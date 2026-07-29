#ifndef MPV_KMP_APPLE_H
#define MPV_KMP_APPLE_H

#include <CoreFoundation/CoreFoundation.h>
#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <stddef.h>
#include <stdint.h>

static inline void mpv_kmp_cf_release(CFTypeRef value) {
    if (value != NULL) {
        CFRelease(value);
    }
}

static inline CVReturn mpv_kmp_cv_pixel_buffer_create(
    size_t width,
    size_t height,
    CVPixelBufferRef *pixel_buffer_out
) {
    CFDictionaryRef io_surface_properties = CFDictionaryCreate(
        kCFAllocatorDefault,
        NULL,
        NULL,
        0,
        &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks
    );
    if (io_surface_properties == NULL) {
        return kCVReturnAllocationFailed;
    }

    CFMutableDictionaryRef attributes = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        1,
        &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks
    );
    if (attributes == NULL) {
        CFRelease(io_surface_properties);
        return kCVReturnAllocationFailed;
    }

    CFDictionarySetValue(
        attributes,
        kCVPixelBufferIOSurfacePropertiesKey,
        io_surface_properties
    );
    CVReturn result = CVPixelBufferCreate(
        kCFAllocatorDefault,
        width,
        height,
        kCVPixelFormatType_32BGRA,
        attributes,
        pixel_buffer_out
    );
    CFRelease(attributes);
    CFRelease(io_surface_properties);
    return result;
}

static inline void mpv_kmp_bgra_make_opaque(
    void *base_address,
    size_t bytes_per_row,
    int width,
    int height
) {
    if (base_address == NULL || width <= 0 || height <= 0) {
        return;
    }

    uint8_t *base = (uint8_t *)base_address;
    for (int y = 0; y < height; ++y) {
        uint8_t *row = base + ((size_t)y * bytes_per_row);
        for (int x = 0; x < width; ++x) {
            row[((size_t)x * 4) + 3] = UINT8_MAX;
        }
    }
}

static inline OSStatus mpv_kmp_sample_buffer_create(
    CVImageBufferRef image_buffer,
    CMVideoFormatDescriptionRef format_description,
    int32_t frame_rate,
    CMSampleBufferRef *sample_buffer_out
) {
    int32_t safe_frame_rate = frame_rate > 0 ? frame_rate : 30;
    CMSampleTimingInfo timing = {
        .duration = CMTimeMake(1, safe_frame_rate),
        .presentationTimeStamp = CMClockGetTime(CMClockGetHostTimeClock()),
        .decodeTimeStamp = kCMTimeInvalid,
    };
    OSStatus result = CMSampleBufferCreateForImageBuffer(
        kCFAllocatorDefault,
        image_buffer,
        true,
        NULL,
        NULL,
        format_description,
        &timing,
        sample_buffer_out
    );
    if (result != noErr || sample_buffer_out == NULL || *sample_buffer_out == NULL) {
        return result;
    }

    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(
        *sample_buffer_out,
        true
    );
    if (attachments != NULL && CFArrayGetCount(attachments) > 0) {
        CFMutableDictionaryRef attachment = (CFMutableDictionaryRef)
            CFArrayGetValueAtIndex(attachments, 0);
        CFDictionarySetValue(
            attachment,
            kCMSampleAttachmentKey_DisplayImmediately,
            kCFBooleanTrue
        );
    }
    return result;
}

#endif
