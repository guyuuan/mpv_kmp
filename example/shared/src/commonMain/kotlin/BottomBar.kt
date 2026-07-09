package com.guyuuan.mpv_kmp.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guyuuan.mpv_kmp.MpvPlayer
import com.guyuuan.mpv_kmp.data.MpvAudioTrack
import com.guyuuan.mpv_kmp.data.MpvSubtitleTrack
import com.guyuuan.mpv_kmp.data.TrackItem
import com.guyuuan.mpv_kmp.isIdle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBar(modifier: Modifier = Modifier, playerState: MpvPlayer) {
    val progress by derivedStateOf {
        (playerState.timePos / (playerState.duration.takeIf { it > 0 } ?: 1.0)).toFloat()
    }
    val durationString by remember(playerState.duration, playerState.timePos) {
        derivedStateOf {
            "${playerState.timePos.secondToMMSS()}/${playerState.duration.secondToMMSS()}"
        }
    }
    var showDecoderInfo by remember { mutableStateOf(false) }
    if (showDecoderInfo) {
        val decoderInfo by playerState.decoderInfoFlow.collectAsStateWithLifecycle(null)
        decoderInfo?.let {
            DecoderInfoDialog(decoderInfo = it, onDismissRequest = {
                showDecoderInfo = false
            })
        }
    }
    val isPlaying = playerState.isPlaying || playerState.state.isIdle
    BottomBar(
        modifier = modifier,
        isPlaying = isPlaying,
        progress = progress,
        time = durationString,
        onSeek = {
            playerState.seek(it * playerState.duration)
        },
        onPlay = playerState::play,
        onPause = playerState::pause,
        onClickInfo = {
            showDecoderInfo = true
        },
        getSubtitles = { playerState.getSubtitleList() },
        setSubtitle = { playerState.setSubtitle(it) },
        getAudioTracks = { playerState.getAudioTrackList() },
        setAudioTrack = { playerState.setAudioTrack(it) },
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomBar(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    progress: Float,
    time: String,
    onSeek: (Float) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    getSubtitles: () -> List<MpvSubtitleTrack>,
    setSubtitle: (MpvSubtitleTrack) -> Unit,
    getAudioTracks: () -> List<MpvAudioTrack>,
    setAudioTrack: (MpvAudioTrack) -> Unit,
    onClickInfo: () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(modifier = modifier) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            onPause()
                        } else {
                            onPlay()
                        }
                    }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }
                Slider(value = progress, onValueChange = {
                    onSeek(it)
                }, modifier = Modifier.weight(1f), thumb = {}, track = { state ->
                    SliderDefaults.Track(
                        sliderState = state, drawStopIndicator = null, thumbTrackGapSize = 0.dp
                    )
                })
                Text(text = time, fontFamily = FontFamily.Monospace)
                SubtitleSelector(
                    icon = {
                        Icon(imageVector = Icons.Default.Subtitles, contentDescription = null)
                    }, get = getSubtitles, set = setSubtitle
                )
                AudioTrackSelector(
                    icon = {
                        Icon(imageVector = Icons.Default.Audiotrack, contentDescription = null)
                    }, get = getAudioTracks, set = setAudioTrack
                )
                IconButton(onClick = onClickInfo) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun SubtitleSelector(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    get: () -> List<MpvSubtitleTrack>,
    set: (MpvSubtitleTrack) -> Unit,
) = DropDownSelector(
    modifier = modifier,
    icon = icon,
    get = get,
    set = set,
    title = { (it.title ?: it.language ?: it.externalFilename).toString() })

@Composable
fun AudioTrackSelector(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    get: () -> List<MpvAudioTrack>,
    set: (MpvAudioTrack) -> Unit,
) = DropDownSelector(
    modifier = modifier,
    icon = icon,
    get = get,
    set = set,
    title = { (it.title ?: it.language ?: it.externalFilename).toString() })

@Composable
fun <T : TrackItem> DropDownSelector(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    title: (T) -> String,
    get: () -> List<T>,
    set: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = !expanded }, content = icon)
        DropdownMenu(
            modifier = Modifier.heightIn(max = 150.dp),
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            val list = get()
            for (i in list) {
                DropdownMenuItem(
                    text = {
                        Text(title(i))
                    },
                    onClick = {
                        set(i)
                        expanded = false
                    },
                    trailingIcon = if (i.selected) {
                        {
                            Icon(
                                Icons.Default.Check, contentDescription = null
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

fun Double.secondToMMSS(): String {
    val time = toInt()
    val m = (time / 60).toString().padStart(2, '0')
    val s = (time % 60).toString().padStart(2, '0')
    return "$m:$s"
}