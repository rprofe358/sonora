package org.sonora.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import kotlinx.coroutines.delay

/**
 * La animación de entrada de TK-Music (`res/raw/splash.json`, hecha en After Effects). Se ve una
 * vez al abrir la app, encima de todo, con el color del tema; al terminar se desvanece. El logo
 * toma el color del texto del tema, así se ve en claro y en oscuro.
 */
@Composable
fun Splash() {
    var showing by rememberSaveable { mutableStateOf(true) }
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.splash))
    val progress by animateLottieCompositionAsState(composition, iterations = 1, isPlaying = showing)
    LaunchedEffect(progress) { if (progress >= 1f) showing = false }
    // por si la animación no carga: nunca tapa la app más de unos segundos
    LaunchedEffect(Unit) {
        delay(4_000)
        showing = false
    }
    val ink = MaterialTheme.colorScheme.onBackground.toArgb()
    val dynamics = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.COLOR, ink, "**"),
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, ink, "**"),
    )
    AnimatedVisibility(visible = showing, enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(tween(350))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // se queda con los toques mientras se ve; tocar la salta
                .pointerInput(Unit) { detectTapGestures { showing = false } },
            contentAlignment = Alignment.Center,
        ) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                dynamicProperties = dynamics,
                enableMergePaths = true,
                modifier = Modifier.size(280.dp),
            )
        }
    }
}
