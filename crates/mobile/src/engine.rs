//! Fase 1: el motor que usa la app. Guarda las sesiones de cada proveedor, busca, y reproduce
//! una cola con avance automático. Todo lo que pasa en la reproducción llega a Swift por
//! [`EngineListener`], desde un hilo de tokio, así que Swift debe pasarlo al hilo principal.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use music::equalizer::{Curve, EqualizerWriter};
use music::spotify::SpotifyProvider;
use music::youtube::YouTubeProvider;
use music::{
    Analyzer, Meter, MusicApi, MusicProvider, PlaybackConfig, PlaybackEvent, PlaybackEvents,
    PlaybackFactory, Player, ProviderSession, Quality, SignIn, SignInPrompt, Track, Transcode,
};
use tokio::sync::mpsc::{UnboundedSender, unbounded_channel};
use tokio::task::JoinHandle;

use crate::{MobileError, failed, runtime};

mod library;
mod lyrics;
mod scrobble;
mod session;
mod settings;

pub use library::*;
pub use lyrics::*;
pub use scrobble::*;
pub use session::*;
pub use settings::*;

/// El servicio de la música guardada en el teléfono.
const LOCAL: &str = "local";

/// Cada cuánto informa el motor de la posición.
const POSITION_INTERVAL: Duration = Duration::from_millis(500);
/// Pasados estos segundos, "anterior" vuelve al principio de la canción en vez de retroceder.
const RESTART_AFTER: Duration = Duration::from_secs(3);
/// El rango en decibelios del control de volumen, como en la app de escritorio.
const TAPER_DB: f32 = 50.;

/// Cómo iniciar sesión en un proveedor.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SignInMethod {
    /// El flujo propio del proveedor (Spotify: autorizar en el navegador).
    Default,
    /// Sin cuenta (YouTube Music como invitado).
    Anonymous,
    /// Con las cookies de una sesión iniciada en una vista web.
    Secret,
}

/// Un proveedor que la app puede ofrecer.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ProviderInfo {
    pub slug: String,
    pub name: String,
    /// Hay una sesión guardada de antes, que `restore` puede recuperar.
    pub stored: bool,
    pub signed_in: bool,
    pub methods: Vec<SignInMethod>,
}

/// La cuenta con la que se inició sesión.
#[derive(Clone, Debug, uniffi::Record)]
pub struct Account {
    pub slug: String,
    pub id: String,
    pub name: String,
    pub avatar: Option<String>,
    /// Falso para una sesión de invitado.
    pub authenticated: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct AccountOption {
    pub id: String,
    pub name: String,
    pub detail: Option<String>,
}

/// Lo que el proveedor necesita que la app muestre durante el inicio de sesión. La respuesta, si
/// hace falta, vuelve con `Engine::send_input`.
#[derive(Clone, Debug, uniffi::Enum)]
pub enum Prompt {
    /// Abrir esta dirección en un navegador dentro de la app.
    OpenUrl { url: String },
    /// Mostrar un código para escribir en `url`.
    ShowCode { code: String, url: String },
    /// Abrir `url` en una vista web y, cuando haya una cookie de `proof` con valor para `domain`,
    /// enviar todas las cookies de ese dominio como cabecera (`a=1; b=2`).
    WebCookies {
        url: String,
        landing: String,
        domain: String,
        proof: Vec<String>,
        agent: Option<String>,
    },
    /// Elegir una de varias cuentas; se responde con su `id`.
    ChooseAccount { options: Vec<AccountOption> },
}

/// Una canción tal como la ve la app.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TrackItem {
    pub id: String,
    pub title: String,
    pub artists: String,
    pub album: String,
    pub album_id: Option<String>,
    /// El primer artista, para abrir su página.
    pub artist_id: Option<String>,
    pub cover: Option<String>,
    pub duration_ms: u64,
    pub explicit: bool,
    pub playable: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Status {
    Idle,
    Loading,
    Playing,
    Paused,
    Ended,
}

/// Todo lo que la app necesita para pintar el reproductor y la pantalla de bloqueo.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PlaybackState {
    pub status: Status,
    pub provider: Option<String>,
    pub track: Option<TrackItem>,
    pub index: u32,
    pub queue_length: u32,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub volume: f32,
    /// Este iPhone controla una sesión sin sonar: lo que se ve es lo que suena en la sesión.
    pub remote: bool,
}

/// Lo que Swift implementa para enterarse de lo que pasa en el motor.
#[uniffi::export(with_foreign)]
pub trait EngineListener: Send + Sync {
    fn on_prompt(&self, slug: String, prompt: Prompt);
    fn on_state(&self, state: PlaybackState);
    /// Un problema que no detiene la app, para mostrarlo como aviso.
    fn on_problem(&self, message: String);
    /// La Sonora Session en la que está la app cambió, o se acabó (`None`).
    fn on_session(&self, session: Option<SessionInfo>);
}

/// El motor de Sonora para el teléfono.
#[derive(uniffi::Object)]
pub struct Engine {
    inner: Arc<Inner>,
}

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn new(listener: Arc<dyn EngineListener>) -> Arc<Self> {
        let _ = runtime();
        let writer = EqualizerWriter::new(false, Curve::bands(&[]));
        let config = PlaybackConfig {
            normalisation: true,
            gapless: true,
            position_interval: POSITION_INTERVAL,
            gain: gain(1.),
            equalizer: writer.reader(),
            transcode: Transcode::default(),
            quality: Quality::High,
            meter: Meter::default(),
            analyzer: Analyzer::default(),
            vault: None,
        };
        let providers: Vec<Arc<dyn MusicProvider>> = vec![
            Arc::new(YouTubeProvider::new()),
            Arc::new(SpotifyProvider::from_env()),
            // La música guardada en el teléfono; se abre con `open_local`.
            Arc::new(music::local::LocalProvider::new(
                dirs::cache_dir()
                    .unwrap_or_else(std::env::temp_dir)
                    .join("sonora"),
                storage::Database::standard(),
                storage::Cache::standard(),
            )),
        ];
        Arc::new(Self {
            inner: Arc::new(Inner {
                listener,
                providers,
                sessions: Mutex::default(),
                input: Mutex::default(),
                signing: Mutex::default(),
                active: Mutex::default(),
                queue: Mutex::default(),
                now: Mutex::new(Now::default()),
                level: Mutex::new(1.),
                equalizer: Mutex::new(writer),
                config: Mutex::new(config),
                lyrics: lyrics::providers(),
                listening: Mutex::default(),
                scrobbler: Mutex::default(),
                scrobble_cancel: Arc::default(),
                liked: Mutex::default(),
            }),
        })
    }

    pub fn providers(&self) -> Vec<ProviderInfo> {
        let sessions = lock(&self.inner.sessions);
        self.inner
            .providers
            .iter()
            .map(|provider| ProviderInfo {
                slug: provider.slug().to_owned(),
                name: match provider.slug() {
                    LOCAL => "Descargadas".to_owned(),
                    _ => provider.name().to_owned(),
                },
                stored: provider.stored(),
                signed_in: sessions.contains_key(provider.slug()),
                methods: provider
                    .sign_in_options()
                    .into_iter()
                    .filter_map(|method| match method {
                        SignIn::Default => Some(SignInMethod::Default),
                        SignIn::Anonymous => Some(SignInMethod::Anonymous),
                        SignIn::Secret => Some(SignInMethod::Secret),
                        SignIn::Path(_) | SignIn::Credentials { .. } => None,
                    })
                    .collect(),
            })
            .collect()
    }

    pub fn account(&self, slug: String) -> Option<Account> {
        lock(&self.inner.sessions)
            .get(&slug)
            .map(|session| session.account.clone())
    }

    /// Recupera la sesión guardada de un proveedor, si la hay. Bloquea: llamar fuera del hilo
    /// principal.
    pub fn restore(&self, slug: String) -> Result<Option<Account>, MobileError> {
        let provider = self.inner.provider(&slug)?;
        let session = runtime()
            .block_on(async { provider.restore().await })
            .map_err(failed)?;
        Ok(session.map(|session| self.inner.keep(&slug, session)))
    }

    /// Inicia sesión. Mientras tanto llegan `Prompt`s al listener. Bloquea hasta terminar.
    pub fn sign_in(&self, slug: String, method: SignInMethod) -> Result<Account, MobileError> {
        let provider = self.inner.provider(&slug)?;
        let (sender, receiver) = unbounded_channel();
        *lock(&self.inner.input) = Some(sender);
        *lock(&self.inner.signing) = Some(slug.clone());

        let listener = self.inner.listener.clone();
        let web = provider.web_sign_in();
        let prompted = slug.clone();
        let sink: music::PromptSink = Arc::new(move |prompt| {
            let prompt = match prompt {
                SignInPrompt::Url(url) => Prompt::OpenUrl { url },
                SignInPrompt::Code { code, url } => Prompt::ShowCode { code, url },
                SignInPrompt::Accounts(choices) => Prompt::ChooseAccount {
                    options: choices
                        .into_iter()
                        .map(|choice| AccountOption {
                            id: choice.id,
                            name: choice.name,
                            detail: choice.detail,
                        })
                        .collect(),
                },
                SignInPrompt::Secret => match &web {
                    Some(web) => Prompt::WebCookies {
                        url: web.url.to_owned(),
                        landing: web.landing.to_owned(),
                        domain: web.domain.to_owned(),
                        proof: web.proof.iter().map(|name| (*name).to_owned()).collect(),
                        agent: web.agent.map(str::to_owned),
                    },
                    None => return,
                },
            };
            listener.on_prompt(prompted.clone(), prompt);
        });

        let method = match method {
            SignInMethod::Default => SignIn::Default,
            SignInMethod::Anonymous => SignIn::Anonymous,
            SignInMethod::Secret => SignIn::Secret,
        };
        let result = runtime().block_on(async { provider.sign_in(method, sink, receiver).await });
        *lock(&self.inner.input) = None;
        *lock(&self.inner.signing) = None;
        let session = result.map_err(failed)?;
        Ok(self.inner.keep(&slug, session))
    }

    /// Busca la música guardada en estas carpetas del teléfono (y sus subcarpetas) y la deja
    /// como el servicio `local`, con biblioteca, búsqueda y letras. Volver a llamarla vuelve a
    /// buscar. Bloquea: llamar fuera del hilo principal.
    pub fn open_local(&self, folders: Vec<String>) -> Result<Account, MobileError> {
        let provider = self.inner.provider(LOCAL)?;
        let paths = folders
            .into_iter()
            .map(std::path::PathBuf::from)
            .filter(|path| path.is_dir())
            .collect::<Vec<_>>();
        if paths.is_empty() {
            return Err(MobileError::Failed {
                message: "No encontré ninguna de esas carpetas en el teléfono.".to_owned(),
            });
        }
        let sink: music::PromptSink = Arc::new(|_| {});
        let (_sender, receiver) = unbounded_channel();
        let session = runtime()
            .block_on(async { provider.sign_in(SignIn::Path(paths), sink, receiver).await })
            .map_err(failed)?;
        Ok(self.inner.keep(LOCAL, session))
    }

    /// Responde a un `Prompt` (las cookies, o el id de la cuenta elegida).
    pub fn send_input(&self, text: String) {
        if let Some(sender) = lock(&self.inner.input).as_ref() {
            let _ = sender.send(text);
        }
    }

    /// Abandona el inicio de sesión en curso; `sign_in` vuelve con un error.
    pub fn cancel_sign_in(&self) {
        self.inner.scrobble_cancel.notify_waiters();
        lock(&self.inner.input).take();
        let signing = lock(&self.inner.signing).take();
        if let Some(provider) = signing.and_then(|slug| self.inner.provider(&slug).ok()) {
            provider.abandon();
        }
    }

    pub fn sign_out(&self, slug: String) {
        let playing_here = lock(&self.inner.queue).slug.as_deref() == Some(slug.as_str());
        if playing_here {
            self.inner.stop();
        }
        lock(&self.inner.sessions).remove(&slug);
        lock(&self.inner.liked).remove(&slug);
        if let Ok(provider) = self.inner.provider(&slug) {
            provider.sign_out();
        }
        self.inner.notify();
    }

    /// Busca canciones en un proveedor con sesión iniciada. Bloquea.
    pub fn search(&self, slug: String, query: String) -> Result<Vec<TrackItem>, MobileError> {
        let api = self.inner.api(&slug)?;
        let tracks = runtime()
            .block_on(async { api.search(&query).await })
            .map_err(failed)?;
        Ok(tracks.iter().filter_map(item).collect())
    }

    /// Reemplaza la cola por `tracks` y empieza por `index`.
    pub fn play_queue(
        &self,
        slug: String,
        tracks: Vec<TrackItem>,
        index: u32,
    ) -> Result<(), MobileError> {
        if self.inner.remote() {
            return self.inner.remote_play(&slug, &tracks, index as usize);
        }
        let index = index as usize;
        if index >= tracks.len() {
            return Err(MobileError::Failed {
                message: "la canción elegida no está en la cola".to_owned(),
            });
        }
        let player = self.inner.engine_for(&slug)?;
        {
            let mut queue = lock(&self.inner.queue);
            queue.slug = Some(slug);
            queue.tracks = tracks;
        }
        self.inner.load(&player, index, false);
        Ok(())
    }

    pub fn play(&self) {
        if self.inner.remote() {
            return self.inner.remote_change(|state| state.playing = true);
        }
        if let Some(player) = self.inner.player() {
            lock(&self.inner.now).wants = true;
            player.play();
            self.inner.notify();
        }
    }

    pub fn pause(&self) {
        if self.inner.remote() {
            return self.inner.remote_change(|state| state.playing = false);
        }
        if let Some(player) = self.inner.player() {
            lock(&self.inner.now).wants = false;
            player.pause();
            self.inner.notify();
        }
    }

    pub fn toggle(&self) {
        if self.inner.remote() {
            return self
                .inner
                .remote_change(|state| state.playing = !state.playing);
        }
        let status = lock(&self.inner.now).status;
        match status {
            Status::Playing | Status::Loading => self.pause(),
            Status::Ended => {
                if let Some(player) = self.inner.player() {
                    self.inner.load(&player, 0, false);
                }
            }
            Status::Paused | Status::Idle => self.play(),
        }
    }

    pub fn next(&self) {
        if self.inner.remote() {
            return self.inner.remote_change(|state| {
                if !state.upcoming.is_empty() {
                    state.track = Some(state.upcoming.remove(0));
                    state.position = 0;
                    state.playing = true;
                }
            });
        }
        self.inner.advance(false);
    }

    pub fn previous(&self) {
        if self.inner.remote() {
            return self.inner.remote_change(|state| state.position = 0);
        }
        let Some(player) = self.inner.player() else {
            return;
        };
        let position = lock(&self.inner.now).position;
        let index = lock(&self.inner.queue).index;
        if position > RESTART_AFTER || index == 0 {
            {
                let mut now = lock(&self.inner.now);
                now.place(Duration::ZERO);
                now.seeks += 1;
            }
            player.seek(Duration::ZERO);
            self.inner.notify();
        } else {
            self.inner.load(&player, index - 1, false);
        }
    }

    pub fn seek(&self, position_ms: u64) {
        if self.inner.remote() {
            return self
                .inner
                .remote_change(|state| state.position = position_ms);
        }
        if let Some(player) = self.inner.player() {
            let position = Duration::from_millis(position_ms);
            {
                let mut now = lock(&self.inner.now);
                now.place(position);
                now.seeks += 1;
            }
            player.seek(position);
        }
        self.inner.notify();
    }

    /// El volumen de la app, de 0 a 1, aparte del volumen del sistema.
    pub fn set_volume(&self, level: f32) {
        let level = level.clamp(0., 1.);
        *lock(&self.inner.level) = level;
        lock(&self.inner.config).gain = gain(level);
        if let Some(player) = self.inner.player() {
            player.set_gain(gain(level));
        }
        self.inner.notify();
    }

    pub fn state(&self) -> PlaybackState {
        self.inner.state()
    }

    /// La cola completa, para mostrarla.
    pub fn queue(&self) -> Vec<TrackItem> {
        if let Some(queue) = self.inner.remote_queue() {
            return queue;
        }
        lock(&self.inner.queue).tracks.clone()
    }

    /// Agrega canciones a la cola: justo después de la actual (`next`) o al final. Si no suena
    /// nada, o suena otro servicio, empieza a reproducirlas.
    pub fn enqueue(
        &self,
        slug: String,
        tracks: Vec<TrackItem>,
        next: bool,
    ) -> Result<(), MobileError> {
        if tracks.is_empty() {
            return Ok(());
        }
        if self.inner.remote() {
            let added: Vec<Track> = tracks.iter().map(full).collect();
            self.inner.remote_change(move |state| {
                let at = match next {
                    true => 0,
                    false => state.upcoming.len(),
                };
                state.upcoming.splice(at..at, added);
            });
            return Ok(());
        }
        let same = {
            let queue = lock(&self.inner.queue);
            queue.slug.as_deref() == Some(slug.as_str()) && !queue.tracks.is_empty()
        };
        if !same {
            return self.play_queue(slug, tracks, 0);
        }
        let mut queue = lock(&self.inner.queue);
        let at = match next {
            true => (queue.index + 1).min(queue.tracks.len()),
            false => queue.tracks.len(),
        };
        queue.tracks.splice(at..at, tracks);
        queue.preloaded = false;
        drop(queue);
        self.inner.notify();
        Ok(())
    }

    /// Salta a otra canción de la cola.
    pub fn jump(&self, index: u32) {
        if self.inner.remote() {
            let index = index as usize;
            return self.inner.remote_change(move |state| {
                if index >= 1 && index <= state.upcoming.len() {
                    let mut rest = state.upcoming.split_off(index - 1);
                    state.track = Some(rest.remove(0));
                    state.upcoming = rest;
                    state.position = 0;
                    state.playing = true;
                }
            });
        }
        let Some(player) = self.inner.player() else {
            return;
        };
        if (index as usize) < lock(&self.inner.queue).tracks.len() {
            self.inner.load(&player, index as usize, false);
        }
    }

    /// Quita una canción de la cola (no la que suena).
    pub fn remove_from_queue(&self, index: u32) {
        if self.inner.remote() {
            let index = index as usize;
            return self.inner.remote_change(move |state| {
                if index >= 1 && index <= state.upcoming.len() {
                    state.upcoming.remove(index - 1);
                }
            });
        }
        let mut queue = lock(&self.inner.queue);
        let index = index as usize;
        if index >= queue.tracks.len() || index == queue.index {
            return;
        }
        queue.tracks.remove(index);
        if index < queue.index {
            queue.index -= 1;
        }
        queue.preloaded = false;
        drop(queue);
        self.inner.notify();
    }

    /// Vacía lo que viene después de la canción actual, que sigue sonando.
    pub fn clear_queue(&self) {
        if self.inner.remote() {
            return self.inner.remote_change(|state| state.upcoming.clear());
        }
        let mut queue = lock(&self.inner.queue);
        let keep = (queue.index + 1).min(queue.tracks.len());
        queue.tracks.truncate(keep);
        queue.preloaded = false;
        drop(queue);
        self.inner.notify();
    }
}

struct Session {
    api: Arc<dyn MusicApi>,
    playback: Arc<dyn PlaybackFactory>,
    account: Account,
}

/// El reproductor en marcha y la tarea que escucha sus eventos.
struct Active {
    slug: String,
    player: Arc<dyn Player>,
    listen: JoinHandle<()>,
}

impl Drop for Active {
    fn drop(&mut self) {
        self.listen.abort();
    }
}

#[derive(Default)]
struct Queue {
    slug: Option<String>,
    tracks: Vec<TrackItem>,
    index: usize,
    /// Ya se pidió precargar la siguiente de la actual.
    preloaded: bool,
}

struct Now {
    status: Status,
    position: Duration,
    /// Cuándo se supo `position`, para calcular dónde va mientras suena.
    at: Instant,
    duration: Duration,
    /// Si el usuario quiere que suene (aunque esté cargando).
    wants: bool,
    /// Cuántas veces el usuario movió la posición, y cuántas canciones terminaron solas. Las
    /// sesiones los comparan para saber qué cambió aquí.
    seeks: u64,
    ends: u64,
}

impl Default for Now {
    fn default() -> Self {
        Self {
            status: Status::Idle,
            position: Duration::ZERO,
            at: Instant::now(),
            duration: Duration::ZERO,
            wants: false,
            seeks: 0,
            ends: 0,
        }
    }
}

impl Now {
    fn place(&mut self, position: Duration) {
        self.position = position;
        self.at = Instant::now();
    }

    /// Dónde va la canción ahora mismo.
    fn live(&self) -> Duration {
        match self.status {
            Status::Playing => {
                let moved = self.position + self.at.elapsed();
                match self.duration.is_zero() {
                    true => moved,
                    false => moved.min(self.duration),
                }
            }
            _ => self.position,
        }
    }
}

struct Inner {
    listener: Arc<dyn EngineListener>,
    providers: Vec<Arc<dyn MusicProvider>>,
    sessions: Mutex<HashMap<String, Session>>,
    input: Mutex<Option<UnboundedSender<String>>>,
    signing: Mutex<Option<String>>,
    active: Mutex<Option<Active>>,
    queue: Mutex<Queue>,
    now: Mutex<Now>,
    level: Mutex<f32>,
    /// El único que puede cambiar el ecualizador que lee el reproductor.
    equalizer: Mutex<EqualizerWriter>,
    config: Mutex<PlaybackConfig>,
    lyrics: Vec<Arc<dyn music::LyricsProvider>>,
    listening: Mutex<session::Listening>,
    scrobbler: Mutex<scrobble::Scrobbler>,
    /// Corta una vinculación de scrobbling que espera la aprobación en el navegador.
    scrobble_cancel: Arc<tokio::sync::Notify>,
    /// Las canciones guardadas de cada cuenta, para el corazón del reproductor. `None` mientras
    /// se cargan.
    liked: Mutex<HashMap<String, Option<std::collections::HashSet<String>>>>,
}

impl Inner {
    fn provider(&self, slug: &str) -> Result<Arc<dyn MusicProvider>, MobileError> {
        self.providers
            .iter()
            .find(|provider| provider.slug() == slug)
            .cloned()
            .ok_or_else(|| MobileError::Failed {
                message: format!("no existe el proveedor {slug}"),
            })
    }

    fn api(&self, slug: &str) -> Result<Arc<dyn MusicApi>, MobileError> {
        lock(&self.sessions)
            .get(slug)
            .map(|session| session.api.clone())
            .ok_or_else(|| signed_out(slug))
    }

    /// Guarda una sesión nueva. Si reemplaza a la que está sonando, el reproductor viejo se
    /// descarta para que el siguiente salga de la sesión nueva.
    fn keep(&self, slug: &str, session: ProviderSession) -> Account {
        lock(&self.liked).remove(slug);
        let account = Account {
            slug: slug.to_owned(),
            id: session.profile.id,
            name: session.profile.display_name,
            avatar: session.profile.avatar,
            authenticated: session.authenticated,
        };
        lock(&self.sessions).insert(
            slug.to_owned(),
            Session {
                api: session.api,
                playback: session.playback,
                account: account.clone(),
            },
        );
        let mut active = lock(&self.active);
        if active.as_ref().is_some_and(|active| active.slug == slug) {
            active.take();
        }
        account
    }

    fn player(&self) -> Option<Arc<dyn Player>> {
        lock(&self.active)
            .as_ref()
            .map(|active| active.player.clone())
    }

    /// El reproductor del proveedor `slug`, arrancándolo si hace falta. Solo suena uno a la vez.
    fn engine_for(self: &Arc<Self>, slug: &str) -> Result<Arc<dyn Player>, MobileError> {
        let mut active = lock(&self.active);
        if let Some(running) = active.as_ref()
            && running.slug == slug
        {
            return Ok(running.player.clone());
        }
        if let Some(old) = active.take() {
            old.player.pause();
        }
        let factory = lock(&self.sessions)
            .get(slug)
            .map(|session| session.playback.clone())
            .ok_or_else(|| signed_out(slug))?;
        let config = lock(&self.config).clone();
        let (player, events) = {
            let _context = runtime().enter();
            factory.start(config)
        };
        let player: Arc<dyn Player> = Arc::from(player);
        let listener = Arc::clone(self);
        let listen = runtime().spawn(async move { listener.listen(events).await });
        *active = Some(Active {
            slug: slug.to_owned(),
            player: player.clone(),
            listen,
        });
        Ok(player)
    }

    fn stop(&self) {
        if let Some(old) = lock(&self.active).take() {
            old.player.pause();
        }
        *lock(&self.queue) = Queue::default();
        *lock(&self.now) = Now::default();
    }

    async fn listen(self: Arc<Self>, mut events: Box<dyn PlaybackEvents>) {
        while let Some(event) = events.next().await {
            self.handle(event);
        }
    }

    fn current_id(&self) -> Option<String> {
        let queue = lock(&self.queue);
        queue.tracks.get(queue.index).map(|track| track.id.clone())
    }

    /// Si el evento es de la canción actual. Un evento sin id se toma como de la actual.
    fn is_current(&self, id: &Option<String>) -> bool {
        match id {
            None => true,
            Some(id) => self.current_id().as_deref() == Some(id.as_str()),
        }
    }

    fn handle(self: &Arc<Self>, event: PlaybackEvent) {
        match event {
            PlaybackEvent::Loading { id, at } if self.is_current(&id) => {
                let mut now = lock(&self.now);
                now.status = Status::Loading;
                now.place(at);
            }
            PlaybackEvent::Playing { id, at } if self.is_current(&id) => {
                {
                    let mut now = lock(&self.now);
                    now.status = Status::Playing;
                    now.place(at);
                }
                self.preload_next();
            }
            PlaybackEvent::Paused { id, at } if self.is_current(&id) => {
                let mut now = lock(&self.now);
                now.status = Status::Paused;
                now.place(at);
            }
            PlaybackEvent::Position { id, at } | PlaybackEvent::Seeked { id, at }
                if self.is_current(&id) =>
            {
                lock(&self.now).place(at);
            }
            PlaybackEvent::Length { id, duration } if self.is_current(&id) => {
                lock(&self.now).duration = duration;
            }
            PlaybackEvent::Ended { id } if self.is_current(&id) => {
                lock(&self.now).ends += 1;
                self.advance(true);
                return;
            }
            PlaybackEvent::Unavailable { id } if self.is_current(&id) => {
                self.listener
                    .on_problem("Esta canción no está disponible; paso a la siguiente.".into());
                self.advance(false);
                return;
            }
            PlaybackEvent::Throttled { id } if self.is_current(&id) => {
                self.listener.on_problem(
                    "El servicio está limitando las peticiones; intenta de nuevo en un rato."
                        .into(),
                );
                lock(&self.now).status = Status::Paused;
            }
            PlaybackEvent::Refused => {
                self.listener.on_problem(
                    "El servicio rechazó la reproducción (¿la cuenta está sonando en otro lado?)."
                        .into(),
                );
                lock(&self.now).status = Status::Paused;
            }
            PlaybackEvent::Gated => {
                self.listener
                    .on_problem("El servicio pide una suscripción para esta canción.".into());
                lock(&self.now).status = Status::Paused;
            }
            PlaybackEvent::OutputChanged { heard } => {
                self.recover(heard.map(|(_, at)| at));
                return;
            }
            _ => return,
        }
        self.notify();
    }

    /// Pide precargar la siguiente de la cola para que el paso sea sin cortes.
    fn preload_next(&self) {
        let next = {
            let mut queue = lock(&self.queue);
            if queue.preloaded {
                return;
            }
            queue.preloaded = true;
            queue
                .tracks
                .get(queue.index + 1)
                .map(|track| track.id.clone())
        };
        if let (Some(id), Some(player)) = (next, self.player())
            && let Err(error) = player.preload(&id, true)
        {
            log::warn!("mobile: no se pudo precargar {id}: {error:#}");
        }
    }

    /// Pasa a la siguiente de la cola. `seamless` es el paso natural al terminar una canción.
    fn advance(&self, seamless: bool) {
        let Some(player) = self.player() else {
            return;
        };
        let (next, length) = {
            let queue = lock(&self.queue);
            (queue.index + 1, queue.tracks.len())
        };
        if next < length {
            self.load(&player, next, seamless);
        } else {
            let mut now = lock(&self.now);
            now.status = Status::Ended;
            let end = now.duration;
            now.place(end);
            now.wants = false;
            drop(now);
            self.notify();
        }
    }

    fn load(&self, player: &Arc<dyn Player>, index: usize, seamless: bool) {
        let track = {
            let mut queue = lock(&self.queue);
            queue.index = index;
            queue.preloaded = false;
            queue.tracks.get(index).cloned()
        };
        let Some(track) = track else {
            return;
        };
        {
            let mut now = lock(&self.now);
            now.status = Status::Loading;
            now.place(Duration::ZERO);
            now.wants = true;
            now.duration = Duration::from_millis(track.duration_ms);
        }
        self.notify();
        if let Err(error) = player.load(&track.id, Duration::ZERO, seamless) {
            self.listener
                .on_problem(format!("No se pudo cargar «{}»: {error:#}", track.title));
        }
    }

    /// La salida de audio cambió (audífonos, Bluetooth, una llamada) y el reproductor se cayó:
    /// arranca otro y deja la canción en pausa donde iba.
    fn recover(self: &Arc<Self>, at: Option<Duration>) {
        let slug = lock(&self.active).take().map(|old| old.slug.clone());
        let (Some(slug), Some(id)) = (slug, self.current_id()) else {
            return;
        };
        let at = at.unwrap_or_else(|| lock(&self.now).position);
        let player = match self.engine_for(&slug) {
            Ok(player) => player,
            Err(error) => {
                self.listener.on_problem(error.to_string());
                return;
            }
        };
        lock(&self.queue).preloaded = false;
        if let Err(error) = player.load_paused_at(&id, at) {
            log::warn!("mobile: no se pudo retomar {id}: {error:#}");
        }
        {
            let mut now = lock(&self.now);
            now.status = Status::Paused;
            now.place(at);
        }
        self.notify();
    }

    fn state(&self) -> PlaybackState {
        if let Some(remote) = self.remote_state() {
            return remote;
        }
        let queue = lock(&self.queue);
        let now = lock(&self.now);
        PlaybackState {
            status: now.status,
            provider: queue.slug.clone(),
            track: queue.tracks.get(queue.index).cloned(),
            index: queue.index as u32,
            queue_length: queue.tracks.len() as u32,
            position_ms: now.live().as_millis() as u64,
            duration_ms: now.duration.as_millis() as u64,
            volume: *lock(&self.level),
            remote: false,
        }
    }

    fn notify(&self) {
        self.listener.on_state(self.state());
        self.publish();
        self.scrobble_tick();
    }
}

/// Las canciones completas que la app ya vio, por id, para compartirlas en una sesión tal como
/// las da el servicio.
static KNOWN: LazyLock<Mutex<HashMap<String, Track>>> = LazyLock::new(Mutex::default);
/// Cuántas se recuerdan antes de empezar de nuevo.
const KNOWN_LIMIT: usize = 5000;

/// Convierte una canción del motor en una para la app. Las que no tienen id no se pueden poner.
fn item(track: &Track) -> Option<TrackItem> {
    let id = track.id.clone()?;
    {
        let mut known = lock(&KNOWN);
        if known.len() > KNOWN_LIMIT {
            known.clear();
        }
        known.insert(id, track.clone());
    }
    Some(TrackItem {
        id: track.id.clone()?,
        title: track.name.clone(),
        artists: track.artists.clone(),
        album: track.album.clone(),
        album_id: track.album_id.clone(),
        artist_id: track
            .artist_refs
            .iter()
            .find_map(|artist| artist.id.clone()),
        cover: track.cover.clone(),
        duration_ms: track.duration.as_millis() as u64,
        explicit: track.explicit,
        playable: track.playable,
    })
}

/// La canción completa detrás de una de la app: la que dio el servicio, o una armada con lo que
/// la app sabe de ella.
fn full(item: &TrackItem) -> Track {
    if let Some(track) = lock(&KNOWN).get(&item.id) {
        return track.clone();
    }
    Track {
        id: Some(item.id.clone()),
        name: item.title.clone(),
        playable: item.playable,
        artists: item.artists.clone(),
        artist_refs: item
            .artist_id
            .iter()
            .map(|id| music::ArtistRef {
                name: item.artists.clone(),
                id: Some(id.clone()),
            })
            .collect(),
        album: item.album.clone(),
        album_id: item.album_id.clone(),
        cover: item.cover.clone(),
        duration: Duration::from_millis(item.duration_ms),
        explicit: item.explicit,
        ..Default::default()
    }
}

/// La ganancia lineal de un nivel de volumen, con la misma curva que la app de escritorio.
fn gain(level: f32) -> f32 {
    match level.clamp(0., 1.) {
        level if level <= 0. => 0.,
        level => 10f32.powf(TAPER_DB * (level - 1.) / 20.),
    }
}

fn signed_out(slug: &str) -> MobileError {
    MobileError::Failed {
        message: format!("no has iniciado sesión en {slug}"),
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}
