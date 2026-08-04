package com.guyuuan.kmp.mpv.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun PlaySpeed(modifier: Modifier = Modifier, value: Float, onValueChange: (Float) -> Unit) {
    val str by remember(value) {
        derivedStateOf {
            ((value * 10).roundToInt() / 10f).toString()
        }
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(0.6f), shape = CircleShape) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = { onValueChange(value + 0.1f) }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null,modifier=Modifier.size(24.dp))
            }
            Text(text = str)
            IconButton(onClick = { onValueChange(value - 0.1f) }) {
                Icon(imageVector = Icons.Default.Remove, contentDescription = null,modifier=Modifier.size(24.dp))
            }
        }
    }
}