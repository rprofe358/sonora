//! Aleatorio por género: con el modo encendido, cada canción que empieza elige la siguiente al azar
//! entre las parecidas que da el servicio («radio» de esa canción), sin repetir las últimas.

use std::collections::VecDeque;

use super::{Inner, TrackItem, item, lock};
use crate::runtime;

/// Cuántas canciones recientes no se vuelven a elegir.
const MEMORY: usize = 60;

/// Lo que recuerda el modo aleatorio.
#[derive(Default)]
pub(super) struct Shuffle {
    pub(super) on: bool,
    /// Las últimas canciones que sonaron, para no repetirlas.
    recent: VecDeque<String>,
}

/// Anota la canción que empieza y, con el modo encendido, deja en cola una parecida al azar en
/// lugar de lo que venía.
pub(super) fn advance(inner: &std::sync::Arc<Inner>) {
    let (slug, current) = {
        let queue = lock(&inner.queue);
        let Some(slug) = queue.slug.clone() else {
            return;
        };
        let Some(current) = queue.tracks.get(queue.index).cloned() else {
            return;
        };
        (slug, current)
    };
    {
        let mut shuffle = lock(&inner.shuffle);
        if shuffle.recent.back() != Some(&current.id) {
            shuffle.recent.push_back(current.id.clone());
            while shuffle.recent.len() > MEMORY {
                shuffle.recent.pop_front();
            }
        }
        if !shuffle.on {
            return;
        }
    }
    let Ok(api) = inner.api(&slug) else {
        return;
    };
    let inner = inner.clone();
    runtime().spawn(async move {
        // Las canciones del teléfono no tienen radio: se elige entre todas.
        let found = match api.track_radio(&current.id, None).await {
            Ok((tracks, _)) if !tracks.is_empty() => tracks,
            _ => api.all_tracks().await.unwrap_or_default(),
        };
        let candidates: Vec<TrackItem> = {
            let shuffle = lock(&inner.shuffle);
            found
                .iter()
                .filter_map(item)
                .filter(|track| track.id != current.id && !shuffle.recent.contains(&track.id))
                .collect()
        };
        if candidates.is_empty() {
            return;
        }
        let next = candidates[fastrand::usize(..candidates.len())].clone();
        {
            let mut queue = lock(&inner.queue);
            // si mientras tanto cambió la canción o se apagó el modo, no se toca nada
            let same = queue.tracks.get(queue.index).map(|track| &track.id) == Some(&current.id);
            if !lock(&inner.shuffle).on || !same {
                return;
            }
            let keep = queue.index + 1;
            queue.tracks.truncate(keep);
            queue.tracks.push(next);
            queue.preloaded = false;
        }
        // `notify` puede esperar al motor: fuera de los hilos de tokio
        let _ = tokio::task::spawn_blocking(move || inner.notify()).await;
    });
}
