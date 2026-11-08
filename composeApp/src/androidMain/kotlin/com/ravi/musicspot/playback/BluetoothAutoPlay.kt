package com.ravi.musicspot.playback

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.IoScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playback › Play when Bluetooth connects. When headphones, earbuds, a speaker or a car connects, the last queue
 * resumes. Phones, keyboards and watches are ignored.
 */
class BluetoothAutoPlay : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val cls: BluetoothClass? = runCatching {
            @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.bluetoothClass
        }.getOrNull()
        if (cls != null && cls.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO) return
        val pending = goAsync()
        IoScope.launch {
            try {
                Graph.awaitReady()
                if (!Graph.settings.value.playOnBluetooth) return@launch
                delay(2_500) // let Android route audio to the new device first
                withContext(Dispatchers.Main.immediate) {
                    val p = Graph.player
                    if (!p.state.value.playing && p.state.value.current != null) {
                        p.setPlaying(true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
