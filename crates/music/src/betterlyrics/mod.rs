//! BetterLyrics: letras sincronizadas palabra por palabra (TTML), la misma fuente que usa la app
//! BoomingMusic. Responde con la hoja entera de una vez, sin búsqueda previa.

use anyhow::{Context as _, Result};
use async_trait::async_trait;
use serde::Deserialize;

use crate::lyrics::ttml;
use crate::{LyricsHit, LyricsProvider, LyricsQuery};

const SOURCE: &str = "BetterLyrics";
const URL: &str = "https://lyrics-api.boidu.dev/getLyrics";
const TRUST: u32 = 170;
const AGENT: &str = concat!(
    "sonora/",
    env!("CARGO_PKG_VERSION"),
    " (https://github.com/sonorahq/sonora)"
);

pub struct BetterLyrics {
    http: reqwest::Client,
}

impl BetterLyrics {
    pub fn new() -> Self {
        Self {
            http: reqwest::Client::new(),
        }
    }
}

impl Default for BetterLyrics {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Deserialize)]
struct Answer {
    #[serde(default)]
    ttml: String,
}

#[async_trait]
impl LyricsProvider for BetterLyrics {
    fn name(&self) -> &'static str {
        SOURCE
    }

    async fn search(&self, query: &LyricsQuery) -> Result<Vec<LyricsHit>> {
        let duration = query.duration.as_secs().to_string();
        let mut params = vec![("s", query.title.as_str()), ("a", query.artist.as_str())];
        if !query.duration.is_zero() {
            params.push(("d", duration.as_str()));
        }
        if let Some(album) = query.album.as_deref().filter(|album| !album.is_empty()) {
            params.push(("al", album));
        }
        let response = self
            .http
            .get(URL)
            .query(&params)
            .header("User-Agent", AGENT)
            .send()
            .await
            .context("cannot reach betterlyrics")?;
        let status = response.status();
        if status == reqwest::StatusCode::NOT_FOUND {
            return Ok(Vec::new());
        }
        if !status.is_success() {
            anyhow::bail!("betterlyrics answered with status {status}");
        }
        let answer: Answer = response
            .json()
            .await
            .context("cannot read the betterlyrics response")?;
        if answer.ttml.trim().is_empty() {
            return Ok(Vec::new());
        }
        let sheet = ttml::parse(&answer.ttml)?;
        if sheet.lyrics.is_empty() {
            return Ok(Vec::new());
        }
        // La respuesta no dice de qué canción es: se toma por la pedida.
        Ok(vec![LyricsHit {
            source: SOURCE,
            trust: TRUST,
            lyrics: sheet.lyrics,
            instrumental: false,
            title: query.title.clone(),
            artist: query.artist.clone(),
            album: query.album.clone(),
            duration: (!query.duration.is_zero()).then_some(query.duration),
            writers: sheet.writers,
        }])
    }
}
