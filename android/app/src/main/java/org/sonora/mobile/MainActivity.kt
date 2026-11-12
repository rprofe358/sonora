package org.sonora.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sonora_mobile.audioOutputs
import uniffi.sonora_mobile.engineVersion
import uniffi.sonora_mobile.lyrics
import uniffi.sonora_mobile.prepare
import uniffi.sonora_mobile.providers

/// Guarda el contexto de la app en el motor (cpal lo necesita para AAudio).
object NativeContext {
    init {
        System.loadLibrary("sonora_mobile")
    }

    external fun init(context: Context)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeContext.init(applicationContext)
        prepare(filesDir.absolutePath, cacheDir.absolutePath)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Phase0()
            }
        }
    }
}

/// Fase 0: una pantalla de diagnóstico que llama al motor de Sonora (Rust) desde Kotlin.
@Composable
fun Phase0() {
    var providerList by remember { mutableStateOf(listOf<String>()) }
    var outputs by remember { mutableStateOf(listOf<String>()) }
    var title by remember { mutableStateOf("Bohemian Rhapsody") }
    var artist by remember { mutableStateOf("Queen") }
    var status by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        providerList = withContext(Dispatchers.IO) { providers() }
        outputs = withContext(Dispatchers.IO) {
            runCatching { audioOutputs() }.getOrElse { listOf("Error: ${it.message}") }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Sonora · Fase 0 (Android)", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Versión del motor: ${engineVersion()}") }
            item { Text("Proveedores", style = MaterialTheme.typography.titleMedium) }
            items(providerList) { Text(it) }
            item { Text("Salidas de audio", style = MaterialTheme.typography.titleMedium) }
            items(outputs) { Text(it) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Prueba de red: letras en LRCLIB", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(title, { title = it }, label = { Text("Canción") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        status = "Buscando…"
                        lines = emptyList()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { lyrics(title, artist) } }
                            result.onSuccess {
                                lines = it
                                status = if (it.isEmpty()) "Sin resultados" else "Listo"
                            }.onFailure { status = "Error: ${it.message}" }
                        }
                    }) { Text("Buscar letra") }
                    if (status.isNotEmpty()) Text(status)
                }
            }
            items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
