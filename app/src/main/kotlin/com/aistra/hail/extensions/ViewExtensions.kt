package com.aistra.hail.extensions

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService

val View.isRtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL

fun View.hideKeyboard() {
    context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(windowToken, 0)
}
