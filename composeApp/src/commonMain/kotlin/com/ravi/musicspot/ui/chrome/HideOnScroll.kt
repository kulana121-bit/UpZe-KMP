package com.ravi.musicspot.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

class NavHideState(private val hideAfterPx: Float, private val showAfterPx: Float) {
    var hidden by mutableStateOf(false)
        private set

    var enabled: Boolean = true
        set(v) { field = v; if (!v) show() }

    private var down = 0f
    private var up = 0f

    fun show() { hidden = false; down = 0f; up = 0f }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled) return Offset.Zero
            val dy = consumed.y
            when {
                dy < 0f -> { up = 0f; down += -dy; if (!hidden && down >= hideAfterPx) { hidden = true; down = 0f } }
                dy > 0f -> { down = 0f; up += dy; if (hidden && up >= showAfterPx) show() }
                available.y > 0f -> show()
            }
            return Offset.Zero
        }
    }
}
