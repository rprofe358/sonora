//! Fase 2: la página de inicio, la biblioteca, la búsqueda completa y las páginas de álbum,
//! playlist y artista. Todo bloquea mientras llega la respuesta del servicio, así que Swift
//! llama estas funciones fuera del hilo principal.

use std::future::Future;

use music::{Album, GenreItem, Playlist, ReleaseType, SavedArtist};

use super::{Engine, TrackItem, item, lock};
use crate::{MobileError, failed, runtime};

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AlbumItem {
    pub id: String,
    pub title: String,
    pub artists: String,
    pub artist_id: Option<String>,
    pub cover: Option<String>,
    /// Una portada más grande para la cabecera de la página, si el servicio la tiene.
    pub cover_large: Option<String>,
    /// 0 cuando el servicio no la dice.
    pub year: i32,
    pub track_count: u32,
    /// "Álbum", "Sencillo", "EP"…
    pub kind: String,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ArtistItem {
    pub id: String,
    pub name: String,
    pub cover: Option<String>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PlaylistItem {
    pub id: String,
    pub title: String,
    pub owner: String,
    pub cover: Option<String>,
    /// 0 cuando el servicio no lo dice.
    pub track_count: u32,
    pub owned: bool,
}

/// Una tarjeta de un estante del inicio, o un resultado de búsqueda.
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum MediaItem {
    Track {
        track: TrackItem,
    },
    Album {
        album: AlbumItem,
    },
    Artist {
        artist: ArtistItem,
    },
    Playlist {
        playlist: PlaylistItem,
    },
    /// Un género o estado de ánimo. Todavía no tiene página propia.
    Genre {
        id: String,
        title: String,
        cover: Option<String>,
    },
}

/// Una fila del inicio.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Shelf {
    pub title: String,
    pub items: Vec<MediaItem>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AlbumPage {
    pub album: AlbumItem,
    pub tracks: Vec<TrackItem>,
    pub label: String,
    pub release_date: String,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PlaylistPage {
    pub playlist: PlaylistItem,
    pub tracks: Vec<TrackItem>,
    /// Para pedir el resto con `playlist_more`, si la playlist llegó por partes.
    pub continuation: Option<String>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TrackPage {
    pub tracks: Vec<TrackItem>,
    pub continuation: Option<String>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ArtistPage {
    pub id: String,
    pub name: String,
    pub cover: Option<String>,
    pub banner: Option<String>,
    pub biography: Option<String>,
    pub monthly_listeners: Option<u64>,
    pub top_tracks: Vec<TrackItem>,
    pub albums: Vec<AlbumItem>,
    pub singles: Vec<AlbumItem>,
    pub appears_on: Vec<AlbumItem>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SearchResults {
    pub tracks: Vec<TrackItem>,
    pub albums: Vec<AlbumItem>,
    pub artists: Vec<ArtistItem>,
    pub playlists: Vec<PlaylistItem>,
}

/// Qué se guarda o se quita de la biblioteca.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SavedKind {
    Track,
    Album,
    Artist,
    Playlist,
}

#[uniffi::export]
impl Engine {
    /// La página de inicio del servicio: selección rápida, volver a escuchar y sus estantes.
    pub fn home(&self, slug: String) -> Result<Vec<Shelf>, MobileError> {
        let api = self.inner.api(&slug)?;
        let feed = block(async { api.home().await })?;
        let mut shelves = Vec::new();
        if let Some(picks) = feed.quick_picks {
            shelves.push(Shelf {
                title: "Selección rápida".to_owned(),
                items: picks
                    .iter()
                    .filter_map(item)
                    .map(|track| MediaItem::Track { track })
                    .collect(),
            });
        }
        shelves.push(Shelf {
            title: "Volver a escuchar".to_owned(),
            items: feed.listen_again.iter().map(media).collect(),
        });
        shelves.extend(feed.sections.iter().map(|section| Shelf {
            title: section.title.clone(),
            items: section.items.iter().map(media).collect(),
        }));
        shelves.retain(|shelf| !shelf.items.is_empty());
        Ok(shelves)
    }

    pub fn library_tracks(&self, slug: String) -> Result<Vec<TrackItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        // Las canciones del teléfono son todas las encontradas, no solo las del corazón.
        let tracks = block(async {
            let all = api.all_tracks().await.unwrap_or_default();
            if all.is_empty() { api.saved_tracks().await } else { Ok(all) }
        })?;
        Ok(tracks.iter().filter_map(item).collect())
    }

    pub fn library_albums(&self, slug: String) -> Result<Vec<AlbumItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        let albums = block(async {
            let all = api.all_albums().await.unwrap_or_default();
            if all.is_empty() { api.saved_albums().await } else { Ok(all) }
        })?;
        Ok(albums.iter().map(album).collect())
    }

    pub fn library_artists(&self, slug: String) -> Result<Vec<ArtistItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        let artists = block(async {
            let all = api.all_artists().await.unwrap_or_default();
            if all.is_empty() { api.saved_artists().await } else { Ok(all) }
        })?;
        Ok(artists.iter().map(artist).collect())
    }

    pub fn library_playlists(&self, slug: String) -> Result<Vec<PlaylistItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        let playlists = block(async { api.playlists().await })?;
        Ok(playlists.iter().map(playlist).collect())
    }

    pub fn album(&self, slug: String, id: String) -> Result<AlbumPage, MobileError> {
        let api = self.inner.api(&slug)?;
        let detail = block(async { api.album(&id).await })?;
        Ok(AlbumPage {
            label: detail.album.label.clone(),
            release_date: detail.album.release_date.clone(),
            album: album(&detail.album),
            tracks: detail.tracks.iter().filter_map(item).collect(),
        })
    }

    pub fn playlist(&self, slug: String, id: String) -> Result<PlaylistPage, MobileError> {
        let api = self.inner.api(&slug)?;
        let detail = block(async { api.playlist(&id).await })?;
        Ok(PlaylistPage {
            playlist: playlist(&detail.playlist),
            tracks: detail.tracks.iter().filter_map(item).collect(),
            continuation: detail.continuation,
        })
    }

    /// El siguiente trozo de una playlist larga.
    pub fn playlist_more(
        &self,
        slug: String,
        continuation: String,
    ) -> Result<TrackPage, MobileError> {
        let api = self.inner.api(&slug)?;
        let (tracks, continuation) =
            block(async { api.playlist_continuation(&continuation).await })?;
        Ok(TrackPage {
            tracks: tracks.iter().filter_map(item).collect(),
            continuation,
        })
    }

    pub fn artist(&self, slug: String, id: String) -> Result<ArtistPage, MobileError> {
        let api = self.inner.api(&slug)?;
        let (overview, catalogue) = block(async {
            let overview = api.artist(&id).await?;
            let catalogue = match api.artist_catalogue(&id, &overview.top_tracks).await {
                Ok(catalogue) => catalogue,
                Err(error) => {
                    log::warn!("mobile: sin discografía completa de {id}: {error:#}");
                    Default::default()
                }
            };
            Ok((overview, catalogue))
        })?;
        let top = match catalogue.top_tracks.is_empty() {
            true => &overview.top_tracks,
            false => &catalogue.top_tracks,
        };
        let releases = match catalogue.albums.is_empty() {
            true => &overview.albums,
            false => &catalogue.albums,
        };
        let (singles, albums): (Vec<&Album>, Vec<&Album>) = releases
            .iter()
            .partition(|album| matches!(album.release_type, ReleaseType::Single | ReleaseType::Ep));
        Ok(ArtistPage {
            id,
            name: overview.name.clone(),
            cover: overview.cover_large.clone(),
            banner: overview.banner.clone(),
            biography: overview.biography.clone(),
            monthly_listeners: overview.monthly_listeners,
            top_tracks: top.iter().filter_map(item).collect(),
            albums: albums.into_iter().map(album).collect(),
            singles: singles.into_iter().map(album).collect(),
            appears_on: catalogue.appears_on.iter().map(album).collect(),
        })
    }

    /// Las canciones de un artista para reproducir, las populares primero.
    pub fn artist_tracks(&self, slug: String, id: String) -> Result<Vec<TrackItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        let tracks = block(async { api.artist_tracks(&id).await })?;
        Ok(tracks.iter().filter_map(item).collect())
    }

    /// Busca canciones, álbumes, artistas y playlists a la vez.
    pub fn search_all(&self, slug: String, query: String) -> Result<SearchResults, MobileError> {
        let api = self.inner.api(&slug)?;
        let (tracks, albums, artists, playlists) = runtime().block_on(async {
            tokio::join!(
                api.search(&query),
                api.search_albums(&query),
                api.search_artists(&query),
                api.search_playlists(&query),
            )
        });
        let tracks = tracks.map_err(failed)?;
        Ok(SearchResults {
            tracks: tracks.iter().filter_map(item).collect(),
            albums: albums.unwrap_or_default().iter().map(album).collect(),
            artists: artists.unwrap_or_default().iter().map(artist).collect(),
            playlists: playlists.unwrap_or_default().iter().map(playlist).collect(),
        })
    }

    /// Una radio que empieza en esta canción: ella y otras parecidas.
    pub fn radio(&self, slug: String, track_id: String) -> Result<Vec<TrackItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        let (tracks, _) = block(async { api.track_radio(&track_id, None).await })?;
        Ok(tracks.iter().filter_map(item).collect())
    }

    /// Crea una playlist vacía y devuelve su id.
    pub fn create_playlist(&self, slug: String, name: String) -> Result<String, MobileError> {
        let api = self.inner.api(&slug)?;
        block(async { api.create_playlist(name.trim()).await })
    }

    pub fn rename_playlist(
        &self,
        slug: String,
        id: String,
        name: String,
    ) -> Result<(), MobileError> {
        let api = self.inner.api(&slug)?;
        block(async { api.rename_playlist(&id, name.trim()).await })
    }

    pub fn delete_playlist(&self, slug: String, id: String) -> Result<(), MobileError> {
        let api = self.inner.api(&slug)?;
        block(async { api.delete_playlist(&id).await })
    }

    /// Agrega canciones al final de una playlist, en orden.
    pub fn add_to_playlist(
        &self,
        slug: String,
        playlist: String,
        tracks: Vec<String>,
    ) -> Result<(), MobileError> {
        let api = self.inner.api(&slug)?;
        block(async { api.add_tracks_to_playlist(&playlist, &tracks).await })
    }

    pub fn remove_from_playlist(
        &self,
        slug: String,
        playlist: String,
        track: String,
    ) -> Result<(), MobileError> {
        let api = self.inner.api(&slug)?;
        block(async { api.remove_track_from_playlist(&playlist, &track).await })
    }

    /// Si una canción está entre las guardadas de la cuenta, para el corazón del reproductor.
    /// La primera vez carga la lista en segundo plano y responde `false`; al terminar avisa con
    /// un estado nuevo (`on_state`) para que la app vuelva a preguntar.
    pub fn track_saved(&self, slug: String, id: String) -> bool {
        let mut liked = lock(&self.inner.liked);
        match liked.get(&slug) {
            Some(Some(ids)) => return ids.contains(&id),
            Some(None) => return false,
            None => {}
        }
        let Ok(api) = self.inner.api(&slug) else {
            return false;
        };
        liked.insert(slug.clone(), None);
        drop(liked);
        let inner = self.inner.clone();
        runtime().spawn(async move {
            let ids = match api.saved_tracks().await {
                Ok(tracks) => tracks.into_iter().filter_map(|track| track.id).collect(),
                Err(error) => {
                    log::warn!("library: cannot load saved tracks: {error:#}");
                    Default::default()
                }
            };
            lock(&inner.liked).insert(slug, Some(ids));
            inner.listener.on_state(inner.state());
        });
        false
    }

    /// Guarda algo en la biblioteca del servicio, o lo quita.
    pub fn set_saved(
        &self,
        slug: String,
        kind: SavedKind,
        id: String,
        saved: bool,
    ) -> Result<(), MobileError> {
        let api = self.inner.api(&slug)?;
        if kind == SavedKind::Track
            && let Some(Some(ids)) = lock(&self.inner.liked).get_mut(&slug)
        {
            match saved {
                true => ids.insert(id.clone()),
                false => ids.remove(&id),
            };
        }
        block(async {
            match (kind, saved) {
                (SavedKind::Track, _) => api.set_track_saved(&id, saved).await,
                (SavedKind::Album, _) => api.set_album_saved(&id, saved).await,
                (SavedKind::Artist, _) => api.set_artist_saved(&id, saved).await,
                (SavedKind::Playlist, true) => api.add_playlist_to_library(&id).await,
                (SavedKind::Playlist, false) => api.remove_playlist_from_library(&id).await,
            }
        })
    }
}

fn block<T>(future: impl Future<Output = anyhow::Result<T>>) -> Result<T, MobileError> {
    runtime().block_on(future).map_err(failed)
}

pub(super) fn media(item_: &GenreItem) -> MediaItem {
    match item_ {
        GenreItem::Track(track) => match item(track) {
            Some(track) => MediaItem::Track { track },
            None => MediaItem::Genre {
                id: String::new(),
                title: track.name.clone(),
                cover: track.cover.clone(),
            },
        },
        GenreItem::Album(found) => MediaItem::Album {
            album: album(found),
        },
        GenreItem::Artist(found) => MediaItem::Artist {
            artist: artist(found),
        },
        GenreItem::Playlist(found) => MediaItem::Playlist {
            playlist: playlist(found),
        },
        GenreItem::Genre(genre) => MediaItem::Genre {
            id: genre.id.clone(),
            title: genre.name.clone(),
            cover: genre.cover.clone(),
        },
    }
}

fn album(album: &Album) -> AlbumItem {
    AlbumItem {
        id: album.id.clone(),
        title: album.name.clone(),
        artists: album.artists.clone(),
        artist_id: album
            .artist_refs
            .iter()
            .find_map(|artist| artist.id.clone()),
        cover: album.cover.clone().or_else(|| album.cover_large.clone()),
        cover_large: album.cover_large.clone().or_else(|| album.cover.clone()),
        year: album.year,
        track_count: album.track_count,
        kind: match album.release_type {
            ReleaseType::Album => "Álbum",
            ReleaseType::Single => "Sencillo",
            ReleaseType::Ep => "EP",
            ReleaseType::Compilation => "Recopilación",
            ReleaseType::Audiobook => "Audiolibro",
            ReleaseType::Podcast => "Podcast",
        }
        .to_owned(),
    }
}

fn artist(artist: &SavedArtist) -> ArtistItem {
    ArtistItem {
        id: artist.id.clone(),
        name: artist.name.clone(),
        cover: artist.cover.clone(),
    }
}

fn playlist(playlist: &Playlist) -> PlaylistItem {
    PlaylistItem {
        id: playlist.id.clone(),
        title: playlist.name.clone(),
        owner: playlist.owner.clone(),
        cover: playlist.cover.clone(),
        track_count: playlist.track_count,
        owned: playlist.owned,
    }
}
