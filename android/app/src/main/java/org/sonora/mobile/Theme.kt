package org.sonora.mobile

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** Los temas de color que se eligen en Ajustes › Personalización. */
enum class Palette(val label: String, val seed: Color?) {
    SYSTEM("Del fondo de pantalla", null),
    TK("TK (blanco y negro)", Color(0xFF111111)),
    SUNSET("Atardecer", Color(0xFFE8742C)),
    OCEAN("Océano", Color(0xFF1E88E5)),
    FOREST("Bosque", Color(0xFF2E7D32)),
    LAVENDER("Lavanda", Color(0xFF7E57C2)),
    ROSE("Rosa", Color(0xFFD81B60)),
    GOLD("Oro", Color(0xFFC9A227)),
}

/** Claro, oscuro o como el teléfono. */
enum class Mode(val label: String) {
    SYSTEM("Como el teléfono"),
    LIGHT("Claro"),
    DARK("Oscuro"),
}

/** El tema de la app: el que se eligió, en claro u oscuro. */
@Composable
fun SonoraTheme(content: @Composable () -> Unit) {
    val dark = when (Store.mode) {
        Mode.SYSTEM -> isSystemInDarkTheme()
        Mode.LIGHT -> false
        Mode.DARK -> true
    }
    // iconos de la barra de estado según el modo elegido, no solo el del teléfono
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme(Store.palette, dark), content = content)
}

@Composable
private fun scheme(palette: Palette, dark: Boolean): ColorScheme {
    val seed = palette.seed
    if (seed == null) {
        val context = LocalContext.current
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    }
    if (palette == Palette.TK) {
        return if (dark) {
            darkColorScheme(
                primary = Color.White, onPrimary = Color.Black,
                primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color.White,
                secondary = Color(0xFFBDBDBD), secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color.White,
                tertiary = Color(0xFF9E9E9E),
                background = Color.Black, surface = Color.Black,
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF111111), onPrimary = Color.White,
                primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color(0xFF111111),
                secondary = Color(0xFF424242), secondaryContainer = Color(0xFFEDEDED), onSecondaryContainer = Color(0xFF111111),
                tertiary = Color(0xFF616161),
                background = Color.White, surface = Color.White,
            )
        }
    }
    return if (dark) {
        val primary = lerp(seed, Color.White, 0.35f)
        darkColorScheme(
            primary = primary,
            onPrimary = lerp(seed, Color.Black, 0.75f),
            primaryContainer = lerp(seed, Color.Black, 0.55f),
            onPrimaryContainer = lerp(seed, Color.White, 0.85f),
            secondary = lerp(primary, Color.Gray, 0.35f),
            secondaryContainer = lerp(seed, Color.Black, 0.7f),
            onSecondaryContainer = lerp(seed, Color.White, 0.85f),
            tertiary = lerp(primary, Color.White, 0.3f),
            surfaceTint = primary,
            background = lerp(seed, Color.Black, 0.92f),
            surface = lerp(seed, Color.Black, 0.92f),
            surfaceVariant = lerp(seed, Color.Black, 0.78f),
            surfaceContainer = lerp(seed, Color.Black, 0.86f),
            surfaceContainerHigh = lerp(seed, Color.Black, 0.82f),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.White, 0.8f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.6f),
            secondary = lerp(seed, Color.Gray, 0.35f),
            secondaryContainer = lerp(seed, Color.White, 0.85f),
            onSecondaryContainer = lerp(seed, Color.Black, 0.6f),
            tertiary = lerp(seed, Color.Black, 0.25f),
            surfaceTint = seed,
            background = lerp(seed, Color.White, 0.97f),
            surface = lerp(seed, Color.White, 0.97f),
            surfaceVariant = lerp(seed, Color.White, 0.88f),
            surfaceContainer = lerp(seed, Color.White, 0.92f),
            surfaceContainerHigh = lerp(seed, Color.White, 0.9f),
        )
    }
}
