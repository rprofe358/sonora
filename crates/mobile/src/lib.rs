//! Puente entre el motor de Sonora y la app de iPhone.
//!
//! Fase 0: solo comprueba que el crate `music` compila, enlaza y corre en iOS. Expone unas pocas
//! funciones que tocan cada parte delicada: los proveedores (Spotify y YouTube Music), la salida
//! de audio (cpal sobre CoreAudio) y la red con TLS (rustls + tokio), usando LRCLIB porque no
//! pide cuenta.

use std::fmt;
use std::sync::OnceLock;
use std::time::Duration;

use music::lrclib::LrcLib;
use music::spotify::SpotifyProvider;
use music::youtube::YouTubeProvider;
use music::{Lyrics, LyricsProvider, LyricsQuery, MusicProvider};

mod engine;
mod potoken;

pub use engine::*;
pub use potoken::*;

uniffi::setup_scaffolding!();

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

/// El runtime de tokio que comparte todo el motor. Instala también el proveedor criptográfico de
/// rustls: reqwest se compila sin uno, igual que en la app de escritorio.
fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        rustls::crypto::ring::default_provider()
            .install_default()
            .ok();
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("no se pudo crear el runtime de tokio")
    })
}

#[derive(Debug, uniffi::Error)]
pub enum MobileError {
    Failed { message: String },
}

impl fmt::Display for MobileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            MobileError::Failed { message } => f.write_str(message),
        }
    }
}

impl std::error::Error for MobileError {}

/// Convierte un error del motor en uno que Swift entienda, con toda su cadena de causas.
fn failed(error: anyhow::Error) -> MobileError {
    MobileError::Failed {
        message: format!("{error:#}"),
    }
}

/// La versión de Sonora de la que sale el motor.
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Los proveedores que el motor sabe construir en el teléfono.
#[uniffi::export]
pub fn providers() -> Vec<String> {
    let _ = runtime();
    let youtube = YouTubeProvider::new();
    let spotify = SpotifyProvider::from_env();
    vec![
        format!("{} · guardado: {}", youtube.name(), youtube.stored()),
        format!("{} · guardado: {}", spotify.name(), spotify.stored()),
    ]
}

/// Las salidas de audio que cpal ve en el teléfono.
#[uniffi::export]
pub fn audio_outputs() -> Vec<String> {
    music::output::devices()
        .into_iter()
        .map(|device| {
            if device.system {
                format!("{} (sistema)", device.name)
            } else {
                device.name
            }
        })
        .collect()
}

/// Busca la letra de una canción en LRCLIB. Bloquea: llamarla fuera del hilo principal.
#[uniffi::export]
pub fn lyrics(title: String, artist: String) -> Result<Vec<String>, MobileError> {
    let runtime = runtime();
    let query = LyricsQuery {
        title,
        artist,
        album: None,
        duration: Duration::ZERO,
        track: None,
    };
    let hits = runtime
        .block_on(async { LrcLib::new().search(&query).await })
        .map_err(failed)?;
    let Some(hit) = hits.into_iter().next() else {
        return Ok(Vec::new());
    };
    let header = format!("{} — {} ({})", hit.title, hit.artist, hit.source);
    let mut lines = vec![header];
    match hit.lyrics {
        Lyrics::Plain { text, .. } => lines.extend(text.lines().map(str::to_owned)),
        Lyrics::Synced { lines: synced } => lines.extend(synced.iter().map(|line| {
            let seconds = line.start.as_secs();
            format!("[{:02}:{:02}] {}", seconds / 60, seconds % 60, line.text)
        })),
    }
    Ok(lines)
}
