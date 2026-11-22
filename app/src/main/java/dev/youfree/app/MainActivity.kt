package dev.youfree.app

import android.os.Bundle
import android.app.PictureInPictureParams
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.youfree.app.ui.YouFreeRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YouFreeRoot(viewModel()) }
    }

    fun enterVideoPip() {
        val parameters = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        enterPictureInPictureMode(parameters)
    }
}
