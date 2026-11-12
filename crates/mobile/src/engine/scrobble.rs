//! Scrobbling: anotar lo que se escucha en Last.fm, Libre.fm, ListenBrainz o Maloja, con los
//! mismos clientes y reglas que la app de escritorio (una canción cuenta a la mitad o a los
//! cuatro minutos, y nunca si dura menos de treinta segundos).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use music::scrobble::{Account, Link, Play, Secret, Service};

use super::{Engine, Inner, Prompt, Status, lock};
use crate::{MobileError, failed, runtime};

/// Donde Last.fm y Libre.fm devuelven la aprobación, el mismo que escucha el cliente.
const CALLBACK: &str = "http://127.0.0.1:8990/scrobble";
/// La clave que Libre.fm acepta de cualquiera.
const ANONYMOUS: &str = "sonora";

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ScrobbleLink {
    /// Solo aprobar en el navegador (Libre.fm).
    Browser,
    /// Una API key y su secreto, y luego aprobar en el navegador (Last.fm).
    Keys,
    /// Un token copiado de la página del servicio (ListenBrainz).
    Token,
    /// La dirección de un servidor propio y su clave (Maloja).
    Server,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ScrobbleService {
    pub id: String,
    pub name: String,
    pub link: ScrobbleLink,
    /// Donde se crean las credenciales que pide.
    pub signup: Option<String>,
    pub linked: bool,
    pub account_name: String,
    pub enabled: bool,
}

/// Las cuentas guardadas y la escucha en curso.
#[derive(Default)]
pub(super) struct Scrobbler {
    accounts: HashMap<String, Account>,
    loaded: bool,
    current: Option<Listen>,
}

struct Listen {
    id: String,
    play: Play,
    listened: Duration,
    since: Option<Instant>,
    announced: bool,
    submitted: bool,
}

#[uniffi::export]
impl Engine {
    pub fn scrobble_services(&self) -> Vec<ScrobbleService> {
        let mut scrobbler = lock(&self.inner.scrobbler);
        scrobbler.load();
        music::scrobble::services()
            .iter()
            .map(|service| describe(service.as_ref(), scrobbler.accounts.get(service.id())))
            .collect()
    }

    /// Vincula una cuenta. Para Last.fm y Libre.fm abre la página de aprobación (llega como
    /// `Prompt::OpenUrl`) y espera a que el usuario apruebe. Bloquea.
    pub fn scrobble_connect(
        &self,
        id: String,
        key: String,
        secret: String,
        token: String,
        server: String,
    ) -> Result<ScrobbleService, MobileError> {
        let service = music::scrobble::services()
            .into_iter()
            .find(|service| service.id() == id)
            .ok_or_else(|| MobileError::Failed {
                message: format!("no existe el servicio {id}"),
            })?;
        let link = service.link();
        let credentials = match link {
            Link::Browser => Secret::None,
            Link::Keys => Secret::Keys {
                key: key.clone(),
                secret,
            },
            Link::Token => Secret::Token(token),
            Link::Server => Secret::Server {
                url: server,
                key: key.clone(),
            },
        };
        if matches!(link, Link::Browser | Link::Keys) {
            let authorize = match id.as_str() {
                "lastfm" => "https://www.last.fm/api/auth/",
                _ => "https://libre.fm/api/auth/",
            };
            let api_key = match link {
                Link::Keys => key.trim().to_owned(),
                _ => ANONYMOUS.to_owned(),
            };
            let url = format!(
                "{authorize}?api_key={}&cb={}",
                music::escape::component(&api_key),
                music::escape::component(CALLBACK)
            );
            let listener = self.inner.listener.clone();
            let slug = id.clone();
            // un momento después, para que el cliente ya esté escuchando la vuelta
            runtime().spawn(async move {
                tokio::time::sleep(Duration::from_millis(300)).await;
                listener.on_prompt(slug, Prompt::OpenUrl { url });
            });
        }
        let cancel = self.inner.scrobble_cancel.clone();
        let account = runtime()
            .block_on(async {
                tokio::select! {
                    account = service.connect(credentials) => account,
                    () = cancel.notified() => Err(anyhow::anyhow!("se canceló la vinculación")),
                }
            })
            .map_err(failed)?;
        let mut scrobbler = lock(&self.inner.scrobbler);
        scrobbler.load();
        scrobbler.accounts.insert(id.clone(), account);
        scrobbler.save();
        Ok(describe(service.as_ref(), scrobbler.accounts.get(&id)))
    }

    pub fn scrobble_disconnect(&self, id: String) {
        let mut scrobbler = lock(&self.inner.scrobbler);
        scrobbler.load();
        scrobbler.accounts.remove(&id);
        scrobbler.save();
    }

    pub fn scrobble_set_enabled(&self, id: String, enabled: bool) {
        let mut scrobbler = lock(&self.inner.scrobbler);
        scrobbler.load();
        if let Some(account) = scrobbler.accounts.get_mut(&id) {
            account.enabled = enabled;
        }
        scrobbler.save();
    }
}

impl Scrobbler {
    fn file() -> Option<PathBuf> {
        dirs::data_local_dir().map(|dir| dir.join("sonora").join("scrobble-mobile.json"))
    }

    fn load(&mut self) {
        if self.loaded {
            return;
        }
        self.loaded = true;
        let Some(file) = Self::file() else {
            return;
        };
        if let Ok(text) = std::fs::read_to_string(file) {
            self.accounts = serde_json::from_str(&text).unwrap_or_default();
        }
    }

    fn save(&self) {
        let Some(file) = Self::file() else {
            return;
        };
        if let Some(folder) = file.parent() {
            let _ = std::fs::create_dir_all(folder);
        }
        match serde_json::to_string_pretty(&self.accounts) {
            Ok(text) => {
                if let Err(error) = std::fs::write(&file, text) {
                    log::warn!("scrobble: no se pudieron guardar las cuentas: {error}");
                }
            }
            Err(error) => log::warn!("scrobble: no se pudieron codificar las cuentas: {error}"),
        }
    }

    /// Las cuentas vinculadas y encendidas, con su servicio.
    fn active(&self) -> Vec<(Arc<dyn Service>, Account)> {
        music::scrobble::services()
            .into_iter()
            .filter_map(|service| {
                let account = self.accounts.get(service.id())?;
                (account.linked() && account.enabled).then(|| (service, account.clone()))
            })
            .collect()
    }
}

impl Inner {
    /// Sigue lo que suena: avisa qué suena ahora y anota la escucha cuando ya cuenta. Se llama
    /// con cada cambio del reproductor.
    pub(super) fn scrobble_tick(&self) {
        if self.remote() {
            return;
        }
        let (status, track) = {
            let queue = lock(&self.queue);
            let now = lock(&self.now);
            (now.status, queue.tracks.get(queue.index).cloned())
        };
        let (accounts, announce, submit) = {
            let mut scrobbler = lock(&self.scrobbler);
            scrobbler.load();
            let accounts = scrobbler.active();
            if accounts.is_empty() {
                scrobbler.current = None;
                return;
            }
            let id = track.as_ref().map(|track| track.id.clone());
            if scrobbler.current.as_ref().map(|listen| &listen.id) != id.as_ref() {
                scrobbler.current = track.map(|track| Listen {
                    id: track.id.clone(),
                    play: Play {
                        artist: track.artists,
                        title: track.title,
                        album: (!track.album.is_empty()).then_some(track.album),
                        duration: Duration::from_millis(track.duration_ms),
                        at: SystemTime::now(),
                    },
                    listened: Duration::ZERO,
                    since: None,
                    announced: false,
                    submitted: false,
                });
            }
            let Some(listen) = scrobbler.current.as_mut() else {
                return;
            };
            let mut announce = None;
            if status == Status::Playing {
                if let Some(since) = listen.since {
                    listen.listened += since.elapsed();
                }
                listen.since = Some(Instant::now());
                if !listen.announced {
                    listen.announced = true;
                    announce = Some(listen.play.clone());
                }
            } else if let Some(since) = listen.since.take() {
                listen.listened += since.elapsed();
            }
            let mut submit = None;
            if !listen.submitted && listen.play.earned(listen.listened) {
                listen.submitted = true;
                submit = Some(listen.play.clone());
            }
            (accounts, announce, submit)
        };
        if announce.is_none() && submit.is_none() {
            return;
        }
        runtime().spawn(async move {
            for (service, account) in accounts {
                if let Some(play) = &announce
                    && let Err(error) = service.now_playing(&account, play).await
                {
                    log::warn!(
                        "scrobble: {} no aceptó lo que suena: {error:#}",
                        service.id()
                    );
                }
                if let Some(play) = &submit
                    && let Err(error) = service.scrobble(&account, std::slice::from_ref(play)).await
                {
                    log::warn!("scrobble: {} no aceptó la escucha: {error:#}", service.id());
                }
            }
        });
    }
}

fn describe(service: &dyn Service, account: Option<&Account>) -> ScrobbleService {
    ScrobbleService {
        id: service.id().to_owned(),
        name: match service.id() {
            "lastfm" => "Last.fm",
            "librefm" => "Libre.fm",
            "listenbrainz" => "ListenBrainz",
            "maloja" => "Maloja",
            other => other,
        }
        .to_owned(),
        link: match service.link() {
            Link::Browser => ScrobbleLink::Browser,
            Link::Keys => ScrobbleLink::Keys,
            Link::Token => ScrobbleLink::Token,
            Link::Server => ScrobbleLink::Server,
        },
        signup: service.signup().map(str::to_owned),
        linked: account.is_some_and(Account::linked),
        account_name: account
            .map(|account| account.name.clone())
            .unwrap_or_default(),
        enabled: account.is_some_and(|account| account.enabled),
    }
}
