//! Fase 3: letras. Se pregunta a todos los servicios de letras a la vez, como en la app de
//! escritorio, y se queda la mejor: sincronizada antes que de texto plano.

use std::sync::Arc;
use std::time::Duration;

use music::{Lyrics, LyricsHit, LyricsProvider, LyricsQuery, TrackKey};
use tokio::task::JoinSet;

use super::{Engine, TrackItem};
use crate::{MobileError, runtime};

/// Cuánto se espera a cada servicio antes de seguir sin él.
const PATIENCE: Duration = Duration::from_secs(8);

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct LyricLine {
    pub start_ms: u64,
    pub end_ms: Option<u64>,
    pub text: String,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
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
        let provider = self.inner.provider(&slug)?;
        let query = LyricsQuery {
            title: track.title.clone(),
            artist: track.artists.clone(),
            album: (!track.album.is_empty()).then(|| track.album.clone()),
            duration: Duration::from_millis(track.duration_ms),
            track: Some(TrackKey {
                provider: provider.slug(),
                id: track.id.clone(),
            }),
        };
        let sources = self.inner.lyrics.clone();
        let hits = runtime().block_on(gather(sources, query.clone()));
        let instrumental = music::lyrics::instrumental(&query, &hits);
        let mut ranked = music::lyrics::rank(&query, hits);
        music::lyrics::reshape(&mut ranked);
        let Some(best) = ranked.into_iter().next() else {
            return Ok(instrumental.then(|| LyricsResult {
                synced: false,
                source: String::new(),
                lines: Vec::new(),
                instrumental: true,
            }));
        };
        Ok(Some(result(best)))
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
    let mut hits = Vec::new();
    while let Some(found) = tasks.join_next().await {
        if let Ok(mut found) = found {
            hits.append(&mut found);
        }
    }
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
