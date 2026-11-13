//! Descubrir música: los mixes diarios, armados con lo que se escucha en TK-Music, y los
//! géneros y estados de ánimo de cada servicio.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tokio::task::JoinSet;

use super::library::{MediaItem, Shelf, media};
use super::{Engine, TrackItem, google_sized, item, lock};
use crate::{MobileError, failed, runtime};

/// Cuántas canciones recuerda el historial.
const REMEMBERED: usize = 400;
/// Cuántos mixes salen como mucho, y de cuántas canciones.
const MIXES: usize = 5;
const MIX_LENGTH: usize = 40;
/// Lo escuchado hace menos de esto no vuelve a aparecer en los mixes.
const RESTING: u64 = 24 * 60 * 60;
/// Cambia si cambia la forma de lo guardado, para no leer mixes viejos.
const DISK_VERSION: u32 = 1;

/// Una lista armada para quien escucha.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Mix {
    pub title: String,
    /// «Con …»: otros artistas que salen en el mix.
    pub subtitle: String,
    pub cover: Option<String>,
    pub tracks: Vec<TrackItem>,
}

/// Un género o estado de ánimo, para la sección Explorar.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct GenreTile {
    pub id: String,
    pub title: String,
    pub cover: Option<String>,
}

/// Una canción del historial.
#[derive(Clone, Serialize, Deserialize)]
struct Played {
    slug: String,
    track: TrackItem,
    count: u32,
    /// Segundos desde 1970 de la última vez que sonó.
    last: u64,
}

/// Lo que se escuchó, guardado solo en el teléfono.
static HISTORY: LazyLock<Mutex<Vec<Played>>> = LazyLock::new(|| {
    let saved = path()
        .and_then(|path| std::fs::read(path).ok())
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default();
    Mutex::new(saved)
});

/// Los mixes ya armados hoy, por servicio.
static TODAY: LazyLock<Mutex<HashMap<String, Vec<Mix>>>> = LazyLock::new(Mutex::default);

static DISK: LazyLock<storage::Cache> = LazyLock::new(storage::Cache::standard);

fn path() -> Option<PathBuf> {
    Some(dirs::data_local_dir()?.join("sonora").join("history.json"))
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_secs())
}

/// Anota una canción que empezó a sonar y guarda el historial en segundo plano.
pub(super) fn record(slug: &str, track: &TrackItem) {
    let snapshot = {
        let mut history = lock(&HISTORY);
        let stamp = now();
        match history
            .iter_mut()
            .find(|played| played.slug == slug && played.track.id == track.id)
        {
            Some(played) => {
                played.count += 1;
                played.last = stamp;
                played.track = track.clone();
            }
            None => history.push(Played {
                slug: slug.to_owned(),
                track: track.clone(),
                count: 1,
                last: stamp,
            }),
        }
        if history.len() > REMEMBERED {
            history.sort_by(|a, b| b.last.cmp(&a.last));
            history.truncate(REMEMBERED);
        }
        history.clone()
    };
    runtime().spawn_blocking(move || {
        let Some(path) = path() else {
            return;
        };
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(bytes) = serde_json::to_vec(&snapshot) {
            let _ = std::fs::write(path, bytes);
        }
    });
}

/// El primer artista de una canción, para agrupar.
fn lead(artists: &str) -> String {
    let lowered = artists.to_lowercase();
    let mut end = lowered.len();
    for separator in [",", " & ", " feat", " ft.", " x ", " y ", " and "] {
        if let Some(at) = lowered.find(separator) {
            end = end.min(at);
        }
    }
    // en minúsculas algunas letras cambian de largo: entonces se corta la versión en minúsculas
    if lowered.len() == artists.len() && artists.is_char_boundary(end) {
        artists[..end].trim().to_owned()
    } else {
        lowered[..end].trim().to_owned()
    }
}

#[uniffi::export]
impl Engine {
    /// Los mixes del día para este servicio: uno por cada artista que más se escucha (o, si aún
    /// no hay historial, de las canciones guardadas), con canciones parecidas de ese estilo, sin
    /// lo escuchado en el último día. Se arman una vez al día y se guardan. Bloquea varios
    /// segundos la primera vez.
    pub fn daily_mixes(&self, slug: String) -> Result<Vec<Mix>, MobileError> {
        let day = now() / 86_400;
        let key = format!("{slug}:{day}");
        if let Some(mixes) = lock(&TODAY).get(&key) {
            return Ok(mixes.clone());
        }
        let disk = format!("mobile-mixes:{DISK_VERSION}:{key}");
        if let Ok(Some(Some(mixes))) = DISK.read(&disk, |text| serde_json::from_str::<Vec<Mix>>(text).ok()) {
            lock(&TODAY).insert(key, mixes.clone());
            return Ok(mixes);
        }
        let api = self.inner.api(&slug)?;
        let stamp = now();
        let history: Vec<Played> = lock(&HISTORY)
            .iter()
            .filter(|played| played.slug == slug)
            .cloned()
            .collect();
        let resting: HashSet<String> = history
            .iter()
            .filter(|played| stamp.saturating_sub(played.last) < RESTING)
            .map(|played| played.track.id.clone())
            .collect();

        let mixes = runtime()
            .block_on(async move {
                // Las semillas: lo más escuchado, y lo reciente pesa más.
                let mut seeds: Vec<(TrackItem, f64)> = history
                    .iter()
                    .map(|played| {
                        let days = stamp.saturating_sub(played.last) as f64 / 86_400.0;
                        (played.track.clone(), f64::from(played.count) / (1.0 + days / 7.0))
                    })
                    .collect();
                if seeds.len() < 8 {
                    let recent = api.recently_played().await.unwrap_or_default();
                    seeds.extend(recent.iter().filter_map(item).map(|track| (track, 0.8)));
                    let liked = api.saved_tracks().await.unwrap_or_default();
                    seeds.extend(liked.iter().take(60).filter_map(item).map(|track| (track, 0.5)));
                }
                // Agrupadas por artista, de más a menos escuchado.
                let mut groups: HashMap<String, (String, f64, Vec<(TrackItem, f64)>)> = HashMap::new();
                for (track, score) in seeds {
                    let name = lead(&track.artists);
                    if name.is_empty() {
                        continue;
                    }
                    let entry = groups
                        .entry(name.to_lowercase())
                        .or_insert_with(|| (name.clone(), 0.0, Vec::new()));
                    entry.1 += score;
                    if !entry.2.iter().any(|(seen, _)| seen.id == track.id) {
                        entry.2.push((track, score));
                    }
                }
                let mut ranked: Vec<(String, f64, Vec<(TrackItem, f64)>)> = groups.into_values().collect();
                ranked.sort_by(|a, b| b.1.total_cmp(&a.1));
                ranked.truncate(MIXES);

                let mut mixes = Vec::new();
                for (index, (name, _, mut tracks)) in ranked.into_iter().enumerate() {
                    tracks.sort_by(|a, b| b.1.total_cmp(&a.1));
                    let seeds: Vec<TrackItem> = tracks.into_iter().take(3).map(|(track, _)| track).collect();
                    let mut radios = JoinSet::new();
                    for seed in &seeds {
                        let api = api.clone();
                        let id = seed.id.clone();
                        radios.spawn(async move {
                            tokio::time::timeout(Duration::from_secs(10), api.track_radio(&id, None)).await
                        });
                    }
                    let mut pool: Vec<TrackItem> = Vec::new();
                    let mut seen: HashSet<String> = seeds.iter().map(|seed| seed.id.clone()).collect();
                    while let Some(done) = radios.join_next().await {
                        let Ok(Ok(Ok((found, _)))) = done else {
                            continue;
                        };
                        for track in found.iter().filter_map(item) {
                            if !resting.contains(&track.id) && seen.insert(track.id.clone()) {
                                pool.push(track);
                            }
                        }
                    }
                    if pool.len() < 8 {
                        continue;
                    }
                    // Otro orden cada día, pero el mismo durante el día.
                    let mut rng = fastrand::Rng::with_seed(day.wrapping_mul(31).wrapping_add(index as u64));
                    rng.shuffle(&mut pool);
                    pool.truncate(MIX_LENGTH - seeds.len().min(2));
                    for seed in seeds.iter().take(2) {
                        let at = rng.usize(..=pool.len().min(6));
                        pool.insert(at, seed.clone());
                    }
                    let mut others: Vec<String> = Vec::new();
                    for track in &pool {
                        let other = lead(&track.artists);
                        if !other.is_empty()
                            && !other.eq_ignore_ascii_case(&name)
                            && !others.iter().any(|known| known.eq_ignore_ascii_case(&other))
                        {
                            others.push(other);
                        }
                        if others.len() == 3 {
                            break;
                        }
                    }
                    let subtitle = match others.len() {
                        0 => String::new(),
                        1 => format!("Con {}", others[0]),
                        _ => {
                            let last = others.pop().unwrap_or_default();
                            format!("Con {} y {last}", others.join(", "))
                        }
                    };
                    let cover = seeds
                        .first()
                        .and_then(|seed| seed.cover.as_deref())
                        .map(|cover| google_sized(cover, 600).unwrap_or_else(|| cover.to_owned()));
                    mixes.push(Mix {
                        title: format!("Mix de {name}"),
                        subtitle,
                        cover,
                        tracks: pool,
                    });
                }
                mixes
            });

        if !mixes.is_empty() {
            if let Ok(text) = serde_json::to_string(&mixes) {
                let _ = DISK.write(&disk, &text, stamp as i64);
            }
            lock(&TODAY).insert(key, mixes.clone());
        }
        Ok(mixes)
    }

    /// Los géneros y estados de ánimo del servicio, para Explorar.
    pub fn genres(&self, slug: String) -> Result<Vec<GenreTile>, MobileError> {
        let api = self.inner.api(&slug)?;
        let genres = runtime().block_on(async { api.genres().await }).map_err(failed)?;
        Ok(genres
            .into_iter()
            .map(|genre| GenreTile {
                id: genre.id,
                title: genre.name,
                cover: genre.cover,
            })
            .collect())
    }

    /// La página de un género: filas de playlists y álbumes.
    pub fn genre(&self, slug: String, id: String) -> Result<Vec<Shelf>, MobileError> {
        let api = self.inner.api(&slug)?;
        let detail = runtime().block_on(async { api.genre(&id).await }).map_err(failed)?;
        let mut shelves: Vec<Shelf> = detail
            .sections
            .iter()
            .map(|section| Shelf {
                title: section.title.clone(),
                items: section.items.iter().map(media).collect::<Vec<MediaItem>>(),
            })
            .collect();
        shelves.retain(|shelf| !shelf.items.is_empty());
        Ok(shelves)
    }

    /// Una portada en su mayor tamaño (el original de Spotify, o 1200 píxeles de YouTube), para
    /// mostrarla en grande sin que se vea pixelada.
    pub fn cover_full(&self, slug: String, url: String) -> String {
        if let Ok(api) = self.inner.api(&slug)
            && let Some(full) = api.cover_at(&url, music::CoverSize::Full)
        {
            return full;
        }
        google_sized(&url, 1200).unwrap_or(url)
    }
}
