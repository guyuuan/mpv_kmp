#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#import <dispatch/dispatch.h>
#import <pthread.h>
#include <stdint.h>

typedef void (*mpv_kmp_media_command_callback)(int command, double value);

enum {
    MPV_KMP_COMMAND_PLAY = 0,
    MPV_KMP_COMMAND_PAUSE = 1,
    MPV_KMP_COMMAND_TOGGLE = 2,
    MPV_KMP_COMMAND_STOP = 3,
    MPV_KMP_COMMAND_SEEK_TO = 4,
    MPV_KMP_COMMAND_SEEK_BY = 5,
    MPV_KMP_COMMAND_NEXT = 6,
    MPV_KMP_COMMAND_PREVIOUS = 7,
};

enum {
    MPV_KMP_STATUS_PLAYING = 2,
    MPV_KMP_STATUS_PAUSED = 3,
};

@interface MpvKmpMediaContext : NSObject
@property(nonatomic, assign) mpv_kmp_media_command_callback callback;
@property(nonatomic, strong) NSMutableArray<NSDictionary *> *targets;
@property(nonatomic, strong) NSMutableDictionary *nowPlayingInfo;
@property(nonatomic, copy) NSString *currentMediaId;
@property(nonatomic, assign) BOOL active;
@end

@implementation MpvKmpMediaContext
@end

static void mpv_kmp_on_main(dispatch_block_t block) {
    if (pthread_main_np() != 0) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static NSString *mpv_kmp_string(const char *value) {
    return value == NULL ? nil : [NSString stringWithUTF8String:value];
}

static void mpv_kmp_add_command(
    MpvKmpMediaContext *context,
    MPRemoteCommand *command,
    int command_id,
    double (^value_provider)(MPRemoteCommandEvent *event)
) {
    __weak MpvKmpMediaContext *weak_context = context;
    id token = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        MpvKmpMediaContext *strong_context = weak_context;
        if (strong_context == nil || strong_context.callback == NULL) {
            return MPRemoteCommandHandlerStatusCommandFailed;
        }
        double value = value_provider == nil ? 0.0 : value_provider(event);
        strong_context.callback(command_id, value);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [context.targets addObject:@{ @"command": command, @"token": token }];
}

void *mpv_kmp_media_create(
    mpv_kmp_media_command_callback callback,
    int64_t native_window_handle
) {
    (void) native_window_handle;
    __block MpvKmpMediaContext *context = nil;
    mpv_kmp_on_main(^{
        context = [[MpvKmpMediaContext alloc] init];
        context.callback = callback;
        context.active = YES;
        context.targets = [NSMutableArray array];
        context.nowPlayingInfo = [NSMutableDictionary dictionary];

        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
        mpv_kmp_add_command(context, center.playCommand, MPV_KMP_COMMAND_PLAY, nil);
        mpv_kmp_add_command(context, center.pauseCommand, MPV_KMP_COMMAND_PAUSE, nil);
        mpv_kmp_add_command(context, center.togglePlayPauseCommand, MPV_KMP_COMMAND_TOGGLE, nil);
        mpv_kmp_add_command(context, center.stopCommand, MPV_KMP_COMMAND_STOP, nil);
        mpv_kmp_add_command(context, center.nextTrackCommand, MPV_KMP_COMMAND_NEXT, nil);
        mpv_kmp_add_command(context, center.previousTrackCommand, MPV_KMP_COMMAND_PREVIOUS, nil);
        center.skipForwardCommand.preferredIntervals = @[ @15.0 ];
        center.skipBackwardCommand.preferredIntervals = @[ @15.0 ];
        mpv_kmp_add_command(
            context,
            center.skipForwardCommand,
            MPV_KMP_COMMAND_SEEK_BY,
            ^double(MPRemoteCommandEvent *event) {
                return [(MPSkipIntervalCommandEvent *) event interval] * 1000.0;
            }
        );
        mpv_kmp_add_command(
            context,
            center.skipBackwardCommand,
            MPV_KMP_COMMAND_SEEK_BY,
            ^double(MPRemoteCommandEvent *event) {
                return -[(MPSkipIntervalCommandEvent *) event interval] * 1000.0;
            }
        );
        mpv_kmp_add_command(
            context,
            center.changePlaybackPositionCommand,
            MPV_KMP_COMMAND_SEEK_TO,
            ^double(MPRemoteCommandEvent *event) {
                return [(MPChangePlaybackPositionCommandEvent *) event positionTime] * 1000.0;
            }
        );
    });
    return (__bridge_retained void *) context;
}

void mpv_kmp_media_destroy(void *raw_context) {
    if (raw_context == NULL) return;
    MpvKmpMediaContext *context = (__bridge_transfer MpvKmpMediaContext *) raw_context;
    mpv_kmp_on_main(^{
        for (NSDictionary *target in context.targets) {
            [(MPRemoteCommand *) target[@"command"] removeTarget:target[@"token"]];
        }
        [context.targets removeAllObjects];
        context.active = NO;
        context.currentMediaId = nil;
        context.callback = NULL;
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
        [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
    });
}

void mpv_kmp_media_update_metadata(
    void *raw_context,
    const char *media_id,
    const char *title,
    const char *artist,
    const char *album,
    int media_type,
    const char *artwork_uri,
    const uint8_t *artwork_bytes,
    int artwork_length
) {
    if (raw_context == NULL) return;
    MpvKmpMediaContext *context = (__bridge MpvKmpMediaContext *) raw_context;
    mpv_kmp_on_main(^{
        NSMutableDictionary *info = context.nowPlayingInfo;
        [info removeObjectsForKeys:@[
            MPMediaItemPropertyTitle,
            MPMediaItemPropertyArtist,
            MPMediaItemPropertyAlbumTitle,
            MPMediaItemPropertyArtwork,
            MPNowPlayingInfoPropertyExternalContentIdentifier
        ]];
        NSString *media_id_value = mpv_kmp_string(media_id);
        NSString *title_value = mpv_kmp_string(title);
        NSString *artist_value = mpv_kmp_string(artist);
        NSString *album_value = mpv_kmp_string(album);
        NSString *artwork_uri_value = mpv_kmp_string(artwork_uri);
        context.currentMediaId = media_id_value;
        if (media_id_value != nil) info[MPNowPlayingInfoPropertyExternalContentIdentifier] = media_id_value;
        if (title_value != nil) info[MPMediaItemPropertyTitle] = title_value;
        if (artist_value != nil) info[MPMediaItemPropertyArtist] = artist_value;
        if (album_value != nil) info[MPMediaItemPropertyAlbumTitle] = album_value;
        if (media_type == 1) {
            info[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);
        } else if (media_type == 2) {
            info[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeVideo);
        } else {
            [info removeObjectForKey:MPNowPlayingInfoPropertyMediaType];
        }

        if (artwork_bytes != NULL && artwork_length > 0) {
            NSData *data = [NSData dataWithBytes:artwork_bytes length:(NSUInteger) artwork_length];
            NSImage *image = [[NSImage alloc] initWithData:data];
            if (image != nil) {
                MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc]
                    initWithBoundsSize:image.size
                    requestHandler:^NSImage *(NSSize size) { return image; }];
                info[MPMediaItemPropertyArtwork] = artwork;
            }
        } else if (artwork_uri_value.length > 0 && media_id_value.length > 0) {
            NSURL *url = [NSURL URLWithString:artwork_uri_value];
            NSString *requested_media_id = [media_id_value copy];
            dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
                NSData *data = url == nil ? nil : [NSData dataWithContentsOfURL:url];
                NSImage *image = data == nil ? nil : [[NSImage alloc] initWithData:data];
                if (image == nil) return;
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!context.active ||
                        ![context.currentMediaId isEqualToString:requested_media_id]) {
                        return;
                    }
                    MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc]
                        initWithBoundsSize:image.size
                        requestHandler:^NSImage *(NSSize size) { return image; }];
                    context.nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork;
                    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo =
                        [context.nowPlayingInfo copy];
                });
            });
        }
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = [info copy];
    });
}

void mpv_kmp_media_update_state(
    void *raw_context,
    int status,
    int64_t position_millis,
    int64_t duration_millis,
    double speed,
    int queue_index,
    int queue_size,
    int64_t command_mask,
    int repeat_mode,
    int shuffle_enabled
) {
    if (raw_context == NULL) return;
    MpvKmpMediaContext *context = (__bridge MpvKmpMediaContext *) raw_context;
    (void) repeat_mode;
    (void) shuffle_enabled;
    mpv_kmp_on_main(^{
        NSMutableDictionary *info = context.nowPlayingInfo;
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position_millis / 1000.0);
        info[MPNowPlayingInfoPropertyPlaybackRate] =
            status == MPV_KMP_STATUS_PLAYING ? @(speed) : @0.0;
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @1.0;
        if (duration_millis > 0) {
            info[MPMediaItemPropertyPlaybackDuration] = @(duration_millis / 1000.0);
        } else {
            [info removeObjectForKey:MPMediaItemPropertyPlaybackDuration];
        }
        if (queue_index >= 0) {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = @(queue_index);
        } else {
            [info removeObjectForKey:MPNowPlayingInfoPropertyPlaybackQueueIndex];
        }
        if (queue_size > 0) {
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = @(queue_size);
        } else {
            [info removeObjectForKey:MPNowPlayingInfoPropertyPlaybackQueueCount];
        }

        MPNowPlayingInfoCenter *now_playing = [MPNowPlayingInfoCenter defaultCenter];
        now_playing.nowPlayingInfo = [info copy];
        now_playing.playbackState = status == MPV_KMP_STATUS_PLAYING
            ? MPNowPlayingPlaybackStatePlaying
            : (status == MPV_KMP_STATUS_PAUSED
                ? MPNowPlayingPlaybackStatePaused
                : MPNowPlayingPlaybackStateStopped);

        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
        center.playCommand.enabled = (command_mask & (1LL << 0)) != 0;
        center.pauseCommand.enabled = (command_mask & (1LL << 1)) != 0;
        center.togglePlayPauseCommand.enabled = (command_mask & (1LL << 2)) != 0;
        center.stopCommand.enabled = (command_mask & (1LL << 3)) != 0;
        center.changePlaybackPositionCommand.enabled = (command_mask & (1LL << 4)) != 0;
        center.skipForwardCommand.enabled = (command_mask & (1LL << 5)) != 0;
        center.skipBackwardCommand.enabled = (command_mask & (1LL << 5)) != 0;
        center.nextTrackCommand.enabled = (command_mask & (1LL << 6)) != 0;
        center.previousTrackCommand.enabled = (command_mask & (1LL << 7)) != 0;
    });
}
