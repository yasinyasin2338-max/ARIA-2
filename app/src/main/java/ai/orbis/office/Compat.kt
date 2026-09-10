package ai.orbis.office

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout

/** Small compatibility helpers kept local to Orbis' native UI. */
var EditText.singleLine: Boolean
    get() = maxLines == 1
    set(value) { setSingleLine(value) }

class ScrollView(context: Context) : android.widget.ScrollView(context) {
    class LayoutParams(width: Int, height: Int) : FrameLayout.LayoutParams(width, height)
}
