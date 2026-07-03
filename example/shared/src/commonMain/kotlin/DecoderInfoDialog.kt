package com.guyuuan.mpv_kmp.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.guyuuan.mpv_kmp.MpvDecoderInfo

@Composable
fun DecoderInfoDialog(
    onDismissRequest: () -> Unit, decoderInfo: MpvDecoderInfo
) {
    Dialog(onDismissRequest = onDismissRequest) {
        val (video, audio) = decoderInfo
        Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.small) {
            Column(modifier = Modifier.padding(24.dp).width(280.dp).wrapContentHeight()) {
                Text("Video:", style = MaterialTheme.typography.titleMedium)
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TabRow(key = "Decoder:", value = video.hardwareDecoder)
                    TabRow(key = "Codec:", value = video.codec)
                    TabRow(key = "Desc:", value = video.decoderCodec?:video.codecDescription)
                }
                Text("Audio:", style = MaterialTheme.typography.titleMedium)
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TabRow(key = "Decoder:", value = audio.decoderCodecName)
                    TabRow(key = "Desc:", value = audio.decoderCodec?:audio.codecDescription)
                }
            }
        }
    }
}

@Composable
private fun TabRow(modifier: Modifier = Modifier, key: String, value: String?) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
        )
        Text("$value", modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
    }
}