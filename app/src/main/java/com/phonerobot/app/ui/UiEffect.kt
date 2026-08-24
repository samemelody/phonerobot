package com.phonerobot.app.ui

import androidx.annotation.StringRes
import com.phonerobot.app.R

enum class SnackAction(@StringRes val labelRes: Int) {
    RetryModelLoad(R.string.action_retry),
    RetryInference(R.string.action_retry),
}

sealed interface UiEffect {
    val text: String
    val action: SnackAction?

    data class ShowSnackbar(
        override val text: String,
        override val action: SnackAction? = null,
    ) : UiEffect
}
