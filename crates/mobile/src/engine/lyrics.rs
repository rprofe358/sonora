//! Fase 3: letras. Se pregunta a todos los servicios de letras a la vez, como en la app de
//! escritorio, y se queda la mejor: sincronizada antes que de texto plano.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

use music::{Lyrics, LyricsHit, LyricsProvider, LyricsQuery, TrackKey};
use serde::{Deserialize, Serialize};
use tokio::sync::OnceCell;
use tokio::task::JoinSet;

use super::{Engine, Inner, TrackItem, lock};
use crate::{MobileError, runtime};

/// Cuánto se espera a cada servicio antes de seguir sin él.
const PATIENCE: Duration = Duration::from_secs(6);
/// Cuánto más se espera a los demás servicios una vez que uno ya dio una letra sincronizada,
/// por si alguno trae una mejor (palabra por palabra, o más fiel).
const GRACE: Duration = Duration::from_millis(900);
/// Cuántas letras se recuerdan en memoria.
const REMEMBERED: usize = 300;
/// Cambia si cambia la forma de lo guardado en disco, para no leer letras viejas.
const DISK_VERSION: u32 = 2;

/// Las letras ya buscadas en esta sesión. Cada canción tiene su celda: si dos llamadas piden la
/// misma (la búsqueda anticipada y la pantalla de la letra), la segunda espera a la primera.
type Cells = HashMap<String, Arc<OnceCell<Option<LyricsResult>>>>;
static MEMORY: LazyLock<Mutex<Cells>> = LazyLock::new(Mutex::default);

/// Las letras encontradas, guardadas en `cache.sqlite` para que la próxima vez salgan al instante.
static DISK: LazyLock<storage::Cache> = LazyLock::new(storage::Cache::standard);

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct LyricLine {
    pub start_ms: u64,
    pub end_ms: Option<u64>,
    pub text: String,
    /// Las palabras con sus tiempos, cuando la letra viene palabra por palabra (karaoke). Juntas
    /// forman `text`, espacios incluidos.
    #[serde(default)]
    pub words: Vec<LyricWord>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct LyricWord {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct LyricsResult {
    /// Si las líneas traen tiempos para seguir la canción.
    pub synced: bool,
    /// De qué servicio salieron.
    pub source: String,
    pub lines: Vec<LyricLine>,
    /// La canción es instrumental: no tiene letra.
    pub instrumental: bool,
}

/// Los servicios de letras que tiene la app de escritorio, salvo los de archivos locales.
pub(super) fn providers() -> Vec<Arc<dyn LyricsProvider>> {
    vec![
        // La letra de los archivos del teléfono (.lrc al lado, o en las etiquetas).
        Arc::new(music::local::LocalLyrics),
        // palabra por palabra, como en BoomingMusic
        Arc::new(music::betterlyrics::BetterLyrics::new()),
        Arc::new(music::spotify::SpotifyLyrics::from_env()),
        Arc::new(music::youtube::YouTubeLyrics::new()),
        Arc::new(music::binimum::Binimum::new()),
        Arc::new(music::musixmatch::Musixmatch::new()),
        Arc::new(music::lrclib::LrcLib::new()),
        Arc::new(music::kugou::Kugou::new()),
        Arc::new(music::netease::NetEase::new()),
    ]
}

#[uniffi::export]
impl Engine {
    /// La letra de una canción, o nada si ningún servicio la tiene. Bloquea.
    pub fn lyrics_for(
        &self,
        slug: String,
        track: TrackItem,
    ) -> Result<Option<LyricsResult>, MobileError> {
        let provider = self.inner.provider(&slug)?.slug();
        let sources = self.inner.lyrics.clone();
        Ok(runtime().block_on(resolve(sources, provider, track)))
    }
}

/// Busca de antemano la letra de lo que suena y de lo que viene, para que al abrirla ya esté.
pub(super) fn prefetch(inner: &Inner) {
    let (slug, tracks) = {
        let queue = lock(&inner.queue);
        let Some(slug) = queue.slug.clone() else {
            return;
        };
        let tracks: Vec<TrackItem> = queue.tracks.iter().skip(queue.index).take(2).cloned().collect();
        (slug, tracks)
    };
    let Ok(provider) = inner.provider(&slug) else {
        return;
    };
    let provider = provider.slug();
    for track in tracks {
        let sources = inner.lyrics.clone();
        runtime().spawn(resolve(sources, provider, track));
    }
}

/// La letra de una canción: de memoria, del disco o de los servicios, en ese orden.
async fn resolve(
    sources: Vec<Arc<dyn LyricsProvider>>,
    provider: &'static str,
    track: TrackItem,
) -> Option<LyricsResult> {
    let key = format!("{provider}:{}", track.id);
    let cell = {
        let mut memory = lock(&MEMORY);
        if memory.len() > REMEMBERED {
            memory.clear();
        }
        memory.entry(key.clone()).or_default().clone()
    };
    cell.get_or_init(|| async move {
        let disk = format!("mobile-lyrics:{DISK_VERSION}:{key}");
        let stored = tokio::task::spawn_blocking({
            let disk = disk.clone();
            move || DISK.read(&disk, |text| serde_json::from_str::<LyricsResult>(text).ok())
        })
        .await;
        if let Ok(Ok(Some(Some(found)))) = stored {
            return Some(found);
        }
        let found = search(sources, provider, &track).await;
        if let Some(found) = &found
            && let Ok(text) = serde_json::to_string(found)
        {
            let saved_at = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |since| since.as_secs() as i64);
            let _ = tokio::task::spawn_blocking(move || DISK.write(&disk, &text, saved_at)).await;
        }
        found
    })
    .await
    .clone()
}

/// Pregunta a los servicios y se queda con la mejor letra.
async fn search(
    sources: Vec<Arc<dyn LyricsProvider>>,
    provider: &'static str,
    track: &TrackItem,
) -> Option<LyricsResult> {
    {
        let query = LyricsQuery {
            title: track.title.clone(),
            artist: track.artists.clone(),
            album: (!track.album.is_empty()).then(|| track.album.clone()),
            duration: Duration::from_millis(track.duration_ms),
            track: Some(TrackKey {
                provider,
                id: track.id.clone(),
            }),
        };
        let hits = gather(sources, query.clone()).await;
        let instrumental = music::lyrics::instrumental(&query, &hits);
        let mut ranked = music::lyrics::rank(&query, hits);
        music::lyrics::reshape(&mut ranked);
        let Some(best) = ranked.into_iter().next() else {
            return instrumental.then(|| LyricsResult {
                synced: false,
                source: String::new(),
                lines: Vec::new(),
                instrumental: true,
            });
        };
        Some(result(best))
    }
}

/// Pregunta a todos los servicios a la vez y junta lo que sea de esta canción.
async fn gather(sources: Vec<Arc<dyn LyricsProvider>>, query: LyricsQuery) -> Vec<LyricsHit> {
    let mut tasks = JoinSet::new();
    for source in sources {
        let query = query.clone();
        tasks.spawn(async move {
            let name = source.name();
            match tokio::time::timeout(PATIENCE, source.search(&query)).await {
                Ok(Ok(mut found)) => {
                    found.retain(|hit| music::lyrics::matched(&query, hit));
                    found
                }
                Ok(Err(error)) => {
                    log::warn!("lyrics: {name} no respondió: {error:#}");
                    Vec::new()
                }
                Err(_) => {
                    log::warn!("lyrics: {name} tardó demasiado");
                    Vec::new()
                }
            }
        });
    }
    // En cuanto llega una letra sincronizada, los demás tienen solo `GRACE` más para mejorarla.
    let mut hits = Vec::new();
    let mut deadline: Option<Instant> = None;
    loop {
        let next = match deadline {
            Some(at) => match tokio::time::timeout_at(at.into(), tasks.join_next()).await {
                Ok(next) => next,
                Err(_) => break,
            },
            None => tasks.join_next().await,
        };
        let Some(found) = next else {
            break;
        };
        if let Ok(mut found) = found {
            if deadline.is_none() && found.iter().any(|hit| matches!(hit.lyrics, Lyrics::Synced { .. })) {
                deadline = Some(Instant::now() + GRACE);
            }
            hits.append(&mut found);
        }
    }
    tasks.abort_all();
    hits
}

fn result(hit: LyricsHit) -> LyricsResult {
    let (synced, lines) = match &hit.lyrics {
        Lyrics::Synced { lines } => (
            true,
            lines
                .iter()
                .map(|line| LyricLine {
                    start_ms: line.start.as_millis() as u64,
                    end_ms: line.end.map(|end| end.as_millis() as u64),
                    text: line.text.clone(),
                    words: line
                        .words
                        .iter()
                        .flatten()
                        .map(|word| LyricWord {
                            start_ms: word.start.as_millis() as u64,
                            end_ms: word.end.as_millis() as u64,
                            text: word.text.clone(),
                        })
                        .collect(),
                })
                .collect(),
        ),
        Lyrics::Plain { text, .. } => (
            false,
            text.lines()
                .map(|line| LyricLine {
                    start_ms: 0,
                    end_ms: None,
                    text: line.to_owned(),
                    words: Vec::new(),
                })
                .collect(),
        ),
    };
    LyricsResult {
        synced,
        source: hit.source.to_owned(),
        lines,
        instrumental: hit.instrumental,
    }
}
