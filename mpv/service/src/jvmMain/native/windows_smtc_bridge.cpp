#define NOMINMAX
#include <Windows.h>
#include <roapi.h>
#include <SystemMediaTransportControlsInterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/base.h>

#include <condition_variable>
#include <cstdint>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Media;
using namespace winrt::Windows::Storage::Streams;

using media_command_callback = void (*)(int command, double value);

enum command_id {
    command_play = 0,
    command_pause = 1,
    command_toggle = 2,
    command_stop = 3,
    command_seek_to = 4,
    command_seek_by = 5,
    command_next = 6,
    command_previous = 7,
    command_set_speed = 8,
    command_set_repeat_mode = 10,
    command_set_shuffle = 11,
};

enum playback_status {
    status_playing = 2,
    status_paused = 3,
};

struct callback_state {
    std::mutex mutex;
    media_command_callback callback = nullptr;
    bool active = true;
};

struct media_context {
    std::shared_ptr<callback_state> callbacks;
    int64_t window_handle = 0;
    std::thread worker;
    std::thread::id worker_id;
    std::mutex mutex;
    std::condition_variable condition;
    std::queue<std::function<void()>> tasks;
    bool stopping = false;
    bool initialized = false;
    SystemMediaTransportControls controls{nullptr};
    event_token button_token{};
    event_token position_token{};
    event_token rate_token{};
    event_token repeat_token{};
    event_token shuffle_token{};
};

static std::wstring utf8_string(const char *value) {
    return value == nullptr ? std::wstring{} : to_hstring(std::string(value)).c_str();
}

template <typename Function>
static void run_sync(media_context *context, Function &&function) {
    if (std::this_thread::get_id() == context->worker_id) {
        try {
            function();
        } catch (...) {
        }
        return;
    }
    auto task = std::make_shared<std::packaged_task<void()>>(
        std::forward<Function>(function)
    );
    auto completion = task->get_future();
    {
        std::lock_guard lock(context->mutex);
        if (context->stopping) return;
        context->tasks.emplace([task] { (*task)(); });
    }
    context->condition.notify_one();
    try {
        completion.get();
    } catch (...) {
    }
}

static void invoke(
    std::shared_ptr<callback_state> const &state,
    int command,
    double value = 0.0
) {
    media_command_callback callback = nullptr;
    {
        std::lock_guard lock(state->mutex);
        if (state->active) callback = state->callback;
    }
    if (callback != nullptr) callback(command, value);
}

static void initialize_controls(media_context *context) {
    HWND window = reinterpret_cast<HWND>(context->window_handle);
    if (window == nullptr) window = GetForegroundWindow();
    if (window == nullptr) throw hresult_error(E_HANDLE, L"A Win32 HWND is required for SMTC");

    auto interop = get_activation_factory<
        SystemMediaTransportControls,
        ISystemMediaTransportControlsInterop
    >();
    check_hresult(interop->GetForWindow(
        window,
        guid_of<SystemMediaTransportControls>(),
        put_abi(context->controls)
    ));
    context->controls.IsEnabled(true);
    auto callbacks = context->callbacks;

    context->button_token = context->controls.ButtonPressed(
        [callbacks](auto const &, SystemMediaTransportControlsButtonPressedEventArgs const &event) {
            switch (event.Button()) {
                case SystemMediaTransportControlsButton::Play:
                    invoke(callbacks, command_play);
                    break;
                case SystemMediaTransportControlsButton::Pause:
                    invoke(callbacks, command_pause);
                    break;
                case SystemMediaTransportControlsButton::Stop:
                    invoke(callbacks, command_stop);
                    break;
                case SystemMediaTransportControlsButton::Next:
                    invoke(callbacks, command_next);
                    break;
                case SystemMediaTransportControlsButton::Previous:
                    invoke(callbacks, command_previous);
                    break;
                case SystemMediaTransportControlsButton::FastForward:
                    invoke(callbacks, command_seek_by, 15'000.0);
                    break;
                case SystemMediaTransportControlsButton::Rewind:
                    invoke(callbacks, command_seek_by, -15'000.0);
                    break;
                default:
                    break;
            }
        }
    );
    context->position_token = context->controls.PlaybackPositionChangeRequested(
        [callbacks](auto const &, PlaybackPositionChangeRequestedEventArgs const &event) {
            const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(
                event.RequestedPlaybackPosition()
            ).count();
            invoke(callbacks, command_seek_to, static_cast<double>(millis));
        }
    );
    context->rate_token = context->controls.PlaybackRateChangeRequested(
        [callbacks](auto const &, PlaybackRateChangeRequestedEventArgs const &event) {
            invoke(callbacks, command_set_speed, event.RequestedPlaybackRate());
        }
    );
    context->repeat_token = context->controls.AutoRepeatModeChangeRequested(
        [callbacks](auto const &, AutoRepeatModeChangeRequestedEventArgs const &event) {
            invoke(
                callbacks,
                command_set_repeat_mode,
                static_cast<double>(event.RequestedAutoRepeatMode())
            );
        }
    );
    context->shuffle_token = context->controls.ShuffleEnabledChangeRequested(
        [callbacks](auto const &, ShuffleEnabledChangeRequestedEventArgs const &event) {
            invoke(callbacks, command_set_shuffle, event.RequestedShuffleEnabled() ? 1.0 : 0.0);
        }
    );
}

static void worker_main(media_context *context, std::promise<bool> ready) {
    context->worker_id = std::this_thread::get_id();
    const HRESULT apartment_result = RoInitialize(RO_INIT_MULTITHREADED);
    const bool uninitialize = SUCCEEDED(apartment_result);
    try {
        if (FAILED(apartment_result) && apartment_result != RPC_E_CHANGED_MODE) {
            check_hresult(apartment_result);
        }
        initialize_controls(context);
        context->initialized = true;
        ready.set_value(true);
    } catch (...) {
        ready.set_value(false);
    }

    while (context->initialized) {
        std::function<void()> task;
        {
            std::unique_lock lock(context->mutex);
            context->condition.wait(lock, [context] {
                return context->stopping || !context->tasks.empty();
            });
            if (context->stopping && context->tasks.empty()) break;
            task = std::move(context->tasks.front());
            context->tasks.pop();
        }
        task();
    }

    if (context->controls != nullptr) {
        try {
            context->controls.ButtonPressed(context->button_token);
            context->controls.PlaybackPositionChangeRequested(context->position_token);
            context->controls.PlaybackRateChangeRequested(context->rate_token);
            context->controls.AutoRepeatModeChangeRequested(context->repeat_token);
            context->controls.ShuffleEnabledChangeRequested(context->shuffle_token);
            context->controls.IsEnabled(false);
        } catch (...) {
        }
        context->controls = nullptr;
    }
    if (uninitialize) RoUninitialize();
}

extern "C" __declspec(dllexport) void *mpv_kmp_media_create(
    media_command_callback callback,
    int64_t native_window_handle
) {
    auto context = std::make_unique<media_context>();
    context->callbacks = std::make_shared<callback_state>();
    context->callbacks->callback = callback;
    context->window_handle = native_window_handle;
    std::promise<bool> ready;
    auto completion = ready.get_future();
    context->worker = std::thread(worker_main, context.get(), std::move(ready));
    if (!completion.get()) {
        context->worker.join();
        return nullptr;
    }
    return context.release();
}

extern "C" __declspec(dllexport) void mpv_kmp_media_destroy(void *raw_context) {
    auto context = std::unique_ptr<media_context>(
        static_cast<media_context *>(raw_context)
    );
    if (!context) return;
    {
        std::lock_guard lock(context->callbacks->mutex);
        context->callbacks->active = false;
        context->callbacks->callback = nullptr;
    }
    {
        std::lock_guard lock(context->mutex);
        context->stopping = true;
    }
    context->condition.notify_one();
    if (context->worker.joinable()) context->worker.join();
}

extern "C" __declspec(dllexport) void mpv_kmp_media_update_metadata(
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
    auto context = static_cast<media_context *>(raw_context);
    if (context == nullptr) return;
    const auto media_id_value = utf8_string(media_id);
    const auto title_value = utf8_string(title);
    const auto artist_value = utf8_string(artist);
    const auto album_value = utf8_string(album);
    const auto artwork_uri_value = utf8_string(artwork_uri);
    const std::vector<uint8_t> artwork = artwork_bytes != nullptr && artwork_length > 0
        ? std::vector<uint8_t>(artwork_bytes, artwork_bytes + artwork_length)
        : std::vector<uint8_t>{};

    run_sync(context, [=] {
        auto updater = context->controls.DisplayUpdater();
        updater.ClearAll();
        updater.Type(media_type == 2 ? MediaPlaybackType::Video : MediaPlaybackType::Music);
        if (media_type == 2) {
            auto properties = updater.VideoProperties();
            properties.Title(title_value);
        } else {
            auto properties = updater.MusicProperties();
            properties.Title(title_value);
            properties.Artist(artist_value);
            properties.AlbumTitle(album_value);
        }
        if (!artwork.empty()) {
            InMemoryRandomAccessStream stream;
            DataWriter writer(stream);
            writer.WriteBytes(artwork);
            writer.StoreAsync().get();
            writer.FlushAsync().get();
            stream.Seek(0);
            updater.Thumbnail(RandomAccessStreamReference::CreateFromStream(stream));
        } else if (!artwork_uri_value.empty()) {
            updater.Thumbnail(
                RandomAccessStreamReference::CreateFromUri(Uri(artwork_uri_value))
            );
        }
        updater.Update();
    });
}

extern "C" __declspec(dllexport) void mpv_kmp_media_update_state(
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
    auto context = static_cast<media_context *>(raw_context);
    if (context == nullptr) return;
    run_sync(context, [=] {
        auto &controls = context->controls;
        controls.PlaybackStatus(
            status == status_playing
                ? MediaPlaybackStatus::Playing
                : (status == status_paused
                    ? MediaPlaybackStatus::Paused
                    : MediaPlaybackStatus::Stopped)
        );
        controls.IsPlayEnabled((command_mask & (1LL << 0)) != 0);
        controls.IsPauseEnabled((command_mask & (1LL << 1)) != 0);
        controls.IsStopEnabled((command_mask & (1LL << 3)) != 0);
        controls.IsNextEnabled((command_mask & (1LL << 6)) != 0);
        controls.IsPreviousEnabled((command_mask & (1LL << 7)) != 0);
        controls.IsFastForwardEnabled((command_mask & (1LL << 5)) != 0);
        controls.IsRewindEnabled((command_mask & (1LL << 5)) != 0);
        controls.PlaybackRate(speed);
        controls.AutoRepeatMode(
            repeat_mode == 1
                ? MediaPlaybackAutoRepeatMode::Track
                : (repeat_mode == 2
                    ? MediaPlaybackAutoRepeatMode::List
                    : MediaPlaybackAutoRepeatMode::None)
        );
        controls.ShuffleEnabled(shuffle_enabled != 0);

        SystemMediaTransportControlsTimelineProperties timeline;
        timeline.StartTime(std::chrono::duration_cast<TimeSpan>(std::chrono::milliseconds(0)));
        timeline.MinSeekTime(std::chrono::duration_cast<TimeSpan>(std::chrono::milliseconds(0)));
        timeline.Position(std::chrono::duration_cast<TimeSpan>(
            std::chrono::milliseconds(position_millis)
        ));
        timeline.EndTime(std::chrono::duration_cast<TimeSpan>(
            std::chrono::milliseconds(duration_millis)
        ));
        timeline.MaxSeekTime(std::chrono::duration_cast<TimeSpan>(
            std::chrono::milliseconds(duration_millis)
        ));
        controls.UpdateTimelineProperties(timeline);
    });
}
