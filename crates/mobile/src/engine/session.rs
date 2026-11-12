//! Sonora Session en el teléfono, a partir de `state/src/listening` de la app de escritorio: varias
//! apps de Sonora (el PC incluido) escuchan lo mismo a la vez a través del servidor de sesiones,
//! con un código de seis dígitos. Lo que cualquiera reproduce, pausa o adelanta suena igual para
//! todos.
//!
//! Además, el modo "solo control": el iPhone se une sin sonar y todo lo que se hace en él mueve la
//! sesión, así que sirve de control remoto del PC que está en ella.

use std::collections::VecDeque;
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use futures::{SinkExt as _, StreamExt as _};
use music::Track;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::Message;

use super::{Engine, Inner, PlaybackState, Status, TrackItem, full, item, lock};
use crate::{MobileError, failed, runtime};

/// El servidor oficial de sesiones, el mismo que usa la app de escritorio.
const SERVER: &str = "wss://sessions.sonorahq.org/ws";
/// La versión del protocolo.
const VERSION: u32 = 1;
const CODE_LENGTH: usize = 6;
/// Cuántas canciones siguientes se comparten.
const UPCOMING: usize = 100;
/// Cuánto puede desviarse la reproducción de aquí de la de la sesión antes de corregirla. Es
/// algo más que en escritorio porque el motor informa la posición cada medio segundo.
const DRIFT: Duration = Duration::from_millis(400);
/// Lo mismo justo al pausar o reanudar, cuando un salto no se nota.
const NUDGE: Duration = Duration::from_millis(150);
/// Cada cuánto se compara la reproducción de aquí con la de la sesión.
const CHECK: Duration = Duration::from_secs(1);
/// Cuántas lecturas del reloj del servidor se guardan.
const SAMPLES: usize = 8;
/// Cuántas veces se reintenta una conexión caída.
const RETRIES: u32 = 8;
const PING: Duration = Duration::from_secs(5);
const BURST: u32 = 10;
const BURST_GAP: Duration = Duration::from_millis(150);
const CONNECT: Duration = Duration::from_secs(10);

// ── Protocolo (igual que `state/src/listening/wire.rs`) ─────────────────────────────────────

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    Create {
        version: u32,
        name: String,
        image: Option<String>,
        provider: String,
    },
    Join {
        version: u32,
        code: String,
        name: String,
        image: Option<String>,
        providers: Vec<String>,
    },
    State {
        state: Box<Shared>,
        seq: u64,
    },
    Leave,
    Kick {
        member: String,
    },
    Ping {
        t: u64,
    },
}

#[derive(Clone, Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Reply {
    Joined {
        code: String,
        provider: String,
        you: String,
        members: Vec<Member>,
        state: Option<Shared>,
    },
    Members {
        members: Vec<Member>,
    },
    State {
        state: Shared,
        by: String,
        seq: u64,
    },
    Pong {
        t: u64,
        now: u64,
    },
    Ended,
    Kicked,
    Error {
        reason: Reason,
        #[serde(default)]
        provider: Option<String>,
    },
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum Reason {
    NotFound,
    Provider,
    Full,
    RateLimited,
    Busy,
    TooMany,
    Version,
    Invalid,
    #[serde(other)]
    Unknown,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
struct Member {
    id: String,
    name: String,
    #[serde(default)]
    image: Option<String>,
    host: bool,
}

/// Lo que suena, tal como lo comparte un miembro.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub(super) struct Shared {
    pub track: Option<Track>,
    #[serde(default)]
    pub upcoming: Vec<Track>,
    pub playing: bool,
    /// Dónde iba la canción, en milisegundos, en el momento `at`.
    pub position: u64,
    /// El reloj del servidor, en milisegundos, cuando le llegó el estado.
    #[serde(default)]
    pub at: u64,
}

// ── Lo que ve la app ────────────────────────────────────────────────────────────────────────

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SessionMember {
    pub id: String,
    pub name: String,
    pub image: Option<String>,
    pub host: bool,
    /// Es este iPhone.
    pub you: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SessionInfo {
    pub code: String,
    /// El servicio del que suena la sesión.
    pub provider: String,
    pub members: Vec<SessionMember>,
    /// Este iPhone la inició: si sale, se acaba para todos.
    pub hosting: bool,
    /// Este iPhone solo controla y no suena.
    pub remote: bool,
    /// Se cayó la conexión y se está reintentando.
    pub reconnecting: bool,
}

#[uniffi::export]
impl Engine {
    /// Inicia una sesión con el servicio `slug`. Su código llega por `on_session`.
    pub fn session_start(&self, slug: String) -> Result<(), MobileError> {
        self.inner.api(&slug)?;
        if lock(&self.inner.listening).busy() {
            return Err(message("Ya estás en una sesión."));
        }
        let (name, image) = self.inner.me(&slug);
        self.inner.connect(
            Request::Create {
                version: VERSION,
                name,
                image,
                provider: slug,
            },
            false,
        );
        Ok(())
    }

    /// Se une a la sesión del código. Con `remote`, este iPhone no suena: solo la controla.
    pub fn session_join(&self, code: String, remote: bool) -> Result<(), MobileError> {
        let code: String = code.chars().filter(char::is_ascii_digit).collect();
        if code.len() != CODE_LENGTH {
            return Err(message("El código tiene 6 dígitos."));
        }
        if lock(&self.inner.sessions).is_empty() {
            return Err(message(
                "Primero inicia sesión en el servicio de la sesión (YouTube Music o Spotify).",
            ));
        }
        if lock(&self.inner.listening).busy() {
            self.inner.leave();
        }
        let request = self.inner.join_request(code);
        self.inner.connect(request, remote);
        Ok(())
    }

    /// Sale de la sesión. Si este iPhone la inició, se acaba para todos.
    pub fn session_leave(&self) {
        self.inner.leave();
    }

    /// Saca a alguien de la sesión que este iPhone inició.
    pub fn session_kick(&self, member: String) {
        let listening = lock(&self.inner.listening);
        let hosting = listening.room.as_ref().is_some_and(Room::hosting);
        if let (true, Some(link)) = (hosting, &listening.link) {
            let _ = link.send(Request::Kick { member });
        }
    }

    pub fn session(&self) -> Option<SessionInfo> {
        self.inner.session_info()
    }
}

// ── Estado de la sesión ─────────────────────────────────────────────────────────────────────

struct Room {
    code: String,
    provider: String,
    members: Vec<Member>,
    you: String,
}

impl Room {
    fn hosting(&self) -> bool {
        self.members
            .iter()
            .any(|member| member.host && member.id == self.you)
    }
}

/// Cómo estaba la reproducción de aquí la última vez que se compartió o se ajustó, para
/// distinguir un cambio hecho aquí de uno que vino de la sesión.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Mark {
    track: Option<String>,
    playing: bool,
    upcoming: Vec<String>,
}

#[derive(Default)]
pub(super) struct Listening {
    link: Option<UnboundedSender<Request>>,
    reader: Option<JoinHandle<()>>,
    checker: Option<JoinHandle<()>>,
    retry: Option<JoinHandle<()>>,
    room: Option<Room>,
    /// Se espera la respuesta a crear o unirse.
    asking: bool,
    remote: bool,
    /// Lo último que se oyó o se mandó de la sesión.
    shared: Option<Shared>,
    baseline: Option<Mark>,
    seeks: u64,
    ends: u64,
    /// Se mandó un estado mientras cargaba una canción; al terminar de cargar se manda otro.
    starting: bool,
    /// El último estado propio mandado y el último que el servidor devolvió.
    sent: u64,
    echoed: u64,
    /// Ida y vuelta y diferencia de reloj de los últimos pings, en milisegundos.
    clock: VecDeque<(u64, i64)>,
    retries: u32,
    /// Se está aplicando un estado de la sesión: lo que cambie no se vuelve a compartir.
    applying: bool,
}

impl Listening {
    fn busy(&self) -> bool {
        self.room.is_some() || self.asking
    }

    fn sample(&mut self, t: u64, now: u64) {
        let here = clock();
        let trip = here.saturating_sub(t);
        let offset = now as i64 + (trip / 2) as i64 - here as i64;
        self.clock.push_back((trip, offset));
        if self.clock.len() > SAMPLES {
            self.clock.pop_front();
        }
    }

    /// Cuánto tarda un mensaje en llegar al servidor.
    fn one_way(&self) -> u64 {
        self.clock.iter().map(|(trip, _)| *trip).min().unwrap_or(0) / 2
    }

    /// El reloj del servidor ahora, según la lectura con la ida y vuelta más corta.
    fn server_now(&self) -> u64 {
        let offset = self
            .clock
            .iter()
            .min_by_key(|(trip, _)| *trip)
            .map_or(0, |(_, offset)| *offset);
        clock().saturating_add_signed(offset)
    }

    fn unclamped(&self, state: &Shared) -> Duration {
        let mut at = state.position;
        if state.playing && state.at > 0 {
            at += self.server_now().saturating_sub(state.at);
        }
        Duration::from_millis(at)
    }

    /// Dónde va ahora la canción de la sesión.
    fn expected(&self, state: &Shared) -> Duration {
        let at = self.unclamped(state);
        match state.track.as_ref().map(|track| track.duration) {
            Some(total) if !total.is_zero() => at.min(total),
            _ => at,
        }
    }

    fn past_end(&self, state: &Shared) -> bool {
        state
            .track
            .as_ref()
            .map(|track| track.duration)
            .is_some_and(|total| !total.is_zero() && self.unclamped(state) >= total)
    }

    fn stop_tasks(&mut self) {
        for task in [self.reader.take(), self.checker.take(), self.retry.take()]
            .into_iter()
            .flatten()
        {
            task.abort();
        }
    }
}

impl Inner {
    fn me(&self, slug: &str) -> (String, Option<String>) {
        let (name, image) = lock(&self.sessions)
            .get(slug)
            .map(|session| (session.account.name.clone(), session.account.avatar.clone()))
            .unwrap_or_default();
        match name.trim().is_empty() {
            true => ("iPhone".to_owned(), image),
            false => (name, image),
        }
    }

    fn join_request(&self, code: String) -> Request {
        let providers: Vec<String> = lock(&self.sessions).keys().cloned().collect();
        let first = providers.first().cloned().unwrap_or_default();
        let (name, image) = self.me(&first);
        Request::Join {
            version: VERSION,
            code,
            name,
            image,
            providers,
        }
    }

    fn connect(self: &Arc<Self>, first: Request, remote: bool) {
        let (requests, outgoing) = mpsc::unbounded_channel();
        let (heard, mut incoming) = mpsc::unbounded_channel();
        {
            let mut listening = lock(&self.listening);
            listening.link = Some(requests);
            listening.asking = true;
            listening.remote = remote;
            if let Some(old) = listening.reader.take() {
                old.abort();
            }
        }
        runtime().spawn(run(SERVER.to_owned(), first, outgoing, heard));
        let inner = Arc::clone(self);
        let reader = runtime().spawn(async move {
            while let Some(heard) = incoming.recv().await {
                match heard {
                    Heard::Reply(reply) => inner.on_reply(*reply),
                    Heard::Lost => {
                        inner.lost();
                        break;
                    }
                }
            }
        });
        lock(&self.listening).reader = Some(reader);
        self.session_changed();
    }

    fn on_reply(self: &Arc<Self>, reply: Reply) {
        match reply {
            Reply::Joined {
                code,
                provider,
                you,
                members,
                state,
            } => {
                let (waiting, remote) = {
                    let mut listening = lock(&self.listening);
                    listening.asking = false;
                    listening.retries = 0;
                    listening.retry = None;
                    listening.room = Some(Room {
                        code,
                        provider,
                        members,
                        you,
                    });
                    listening.echoed = listening.sent;
                    listening.baseline = None;
                    listening.shared = state.clone();
                    {
                        let now = lock(&self.now);
                        listening.seeks = now.seeks;
                        listening.ends = now.ends;
                    }
                    listening.starting = false;
                    if listening.checker.is_none() {
                        listening.checker = Some(self.check_every());
                    }
                    (
                        state.filter(|state| state.track.is_some()),
                        listening.remote,
                    )
                };
                if remote {
                    self.pause_here();
                }
                match waiting {
                    Some(state) => self.apply(state),
                    None => self.publish(),
                }
                self.session_changed();
                self.notify();
            }
            Reply::Members { members } => {
                if let Some(room) = lock(&self.listening).room.as_mut() {
                    room.members = members;
                }
                self.session_changed();
            }
            Reply::State { state, by, seq } => {
                let waiting = {
                    let mut listening = lock(&self.listening);
                    let Some(room) = &listening.room else {
                        return;
                    };
                    if by == room.you {
                        listening.echoed = listening.echoed.max(seq);
                        listening.shared = Some(state);
                        None
                    } else if listening.echoed < listening.sent {
                        None
                    } else {
                        listening.shared = Some(state.clone());
                        Some(state)
                    }
                };
                if let Some(state) = waiting {
                    self.apply(state);
                }
            }
            Reply::Pong { t, now } => lock(&self.listening).sample(t, now),
            Reply::Ended => {
                self.reset();
                self.listener.on_problem("La sesión terminó.".into());
            }
            Reply::Kicked => {
                self.reset();
                self.listener
                    .on_problem("Quien inició la sesión te sacó de ella.".into());
            }
            Reply::Error { reason, provider } => self.refused(reason, provider),
        }
    }

    fn refused(&self, reason: Reason, provider: Option<String>) {
        let (asking, rejoining) = {
            let listening = lock(&self.listening);
            (listening.asking, listening.room.is_some())
        };
        if !asking {
            log::warn!("session: el servidor rechazó una petición: {reason:?}");
            return;
        }
        self.reset();
        let text = match reason {
            Reason::NotFound if rejoining => "La sesión terminó.".to_owned(),
            Reason::NotFound => "No existe una sesión con ese código.".to_owned(),
            Reason::Full => "La sesión está llena.".to_owned(),
            Reason::RateLimited => "Demasiados intentos; espera un momento.".to_owned(),
            Reason::TooMany => "Ya iniciaste demasiadas sesiones desde esta red.".to_owned(),
            Reason::Provider => format!(
                "Para unirte necesitas una sesión iniciada en {} en este iPhone.",
                provider.unwrap_or_else(|| "el servicio de la sesión".to_owned())
            ),
            Reason::Busy | Reason::Version | Reason::Invalid | Reason::Unknown => {
                "El servidor de sesiones no pudo atender la petición.".to_owned()
            }
        };
        self.listener.on_problem(text);
    }

    /// La conexión se cayó: se reintenta cada vez más tarde, o se abandona la sesión.
    fn lost(self: &Arc<Self>) {
        let (code, remote, wait) = {
            let mut listening = lock(&self.listening);
            listening.link = None;
            listening.reader = None;
            let Some(code) = listening.room.as_ref().map(|room| room.code.clone()) else {
                let asking = listening.asking;
                drop(listening);
                if asking {
                    self.reset();
                    self.listener
                        .on_problem("No se pudo conectar con el servidor de sesiones.".into());
                }
                return;
            };
            if listening.retries >= RETRIES {
                drop(listening);
                self.reset();
                self.listener
                    .on_problem("Se perdió la conexión con la sesión.".into());
                return;
            }
            let wait = Duration::from_secs(1 << listening.retries.min(4));
            listening.retries += 1;
            listening.asking = false;
            (code, listening.remote, wait)
        };
        let inner = Arc::clone(self);
        let retry = runtime().spawn(async move {
            tokio::time::sleep(wait).await;
            let still = lock(&inner.listening).room.is_some();
            if still {
                let request = inner.join_request(code);
                inner.connect(request, remote);
            }
        });
        lock(&self.listening).retry = Some(retry);
        self.session_changed();
    }

    fn leave(&self) {
        if let Some(link) = &lock(&self.listening).link {
            let _ = link.send(Request::Leave);
        }
        self.reset();
    }

    /// Olvida la sesión y todo lo que se sabía de ella.
    fn reset(&self) {
        let remote = {
            let mut listening = lock(&self.listening);
            listening.stop_tasks();
            let remote = listening.remote;
            *listening = Listening::default();
            remote
        };
        self.session_changed();
        if remote {
            self.notify();
        }
    }

    pub(super) fn session_info(&self) -> Option<SessionInfo> {
        let listening = lock(&self.listening);
        let room = listening.room.as_ref()?;
        Some(SessionInfo {
            code: room.code.clone(),
            provider: room.provider.clone(),
            members: room
                .members
                .iter()
                .map(|member| SessionMember {
                    id: member.id.clone(),
                    name: member.name.clone(),
                    image: member.image.clone(),
                    host: member.host,
                    you: member.id == room.you,
                })
                .collect(),
            hosting: room.hosting(),
            remote: listening.remote,
            reconnecting: listening.retry.is_some() || listening.link.is_none(),
        })
    }

    fn session_changed(&self) {
        self.listener.on_session(self.session_info());
    }

    /// En modo solo control, este iPhone no suena.
    fn pause_here(&self) {
        if let Some(player) = self.player() {
            player.pause();
        }
        lock(&self.now).wants = false;
    }

    fn check_every(self: &Arc<Self>) -> JoinHandle<()> {
        let inner = Arc::clone(self);
        runtime().spawn(async move {
            loop {
                tokio::time::sleep(CHECK).await;
                let remote = {
                    let listening = lock(&inner.listening);
                    if listening.room.is_none() {
                        continue;
                    }
                    listening.remote
                };
                match remote {
                    // así la app ve avanzar la canción que suena en la sesión
                    true => inner.listener.on_state(inner.state()),
                    false => inner.realign(),
                }
            }
        })
    }

    /// Vuelve a poner la reproducción de aquí al paso de la sesión si se desvió, como tras un
    /// corte mientras cargaba.
    fn realign(&self) {
        let (at, id, playing) = {
            let listening = lock(&self.listening);
            if listening.asking || listening.applying || listening.room.is_none() {
                return;
            }
            let Some(state) = &listening.shared else {
                return;
            };
            let Some(id) = state.track.as_ref().and_then(|track| track.id.clone()) else {
                return;
            };
            if listening.past_end(state) {
                return;
            }
            (listening.expected(state), id, state.playing)
        };
        if self.current_id().as_deref() != Some(id.as_str()) {
            return;
        }
        let (live, wants, loading) = {
            let now = lock(&self.now);
            (now.live(), now.wants, now.status == Status::Loading)
        };
        if loading || wants != playing || live.abs_diff(at) <= DRIFT {
            return;
        }
        if let Some(player) = self.player() {
            log::info!(
                "session: {:?} fuera de paso, voy a {at:?}",
                live.abs_diff(at)
            );
            lock(&self.now).place(at);
            player.seek(at);
        }
    }

    /// Hace sonar aquí lo que dice la sesión, desde donde va ahora. Solo toca lo que difiere,
    /// así una pausa de otro miembro no vuelve a cargar la canción.
    fn apply(self: &Arc<Self>, state: Shared) {
        let (slug, at) = {
            let mut listening = lock(&self.listening);
            let Some(room) = &listening.room else {
                return;
            };
            let slug = room.provider.clone();
            let at = listening.expected(&state);
            if listening.remote {
                drop(listening);
                self.notify();
                return;
            }
            listening.applying = true;
            (slug, at)
        };
        let result = self.apply_here(&slug, state, at);
        {
            let mut listening = lock(&self.listening);
            listening.applying = false;
            listening.baseline = Some(self.mark());
            let now = lock(&self.now);
            listening.seeks = now.seeks;
            listening.ends = now.ends;
            listening.starting = false;
        }
        if let Err(error) = result {
            self.listener.on_problem(error.to_string());
        }
        self.notify();
    }

    fn apply_here(
        self: &Arc<Self>,
        slug: &str,
        state: Shared,
        at: Duration,
    ) -> Result<(), MobileError> {
        let Some(track) = state.track.clone() else {
            return Ok(());
        };
        let Some(id) = track.id.clone() else {
            return Ok(());
        };
        let playing = state.playing;
        let same_provider = lock(&self.queue).slug.as_deref() == Some(slug);
        if !same_provider || self.current_id().as_deref() != Some(id.as_str()) {
            let tracks: Vec<TrackItem> = std::iter::once(&track)
                .chain(state.upcoming.iter())
                .filter_map(item)
                .collect();
            let player = self.engine_for(slug)?;
            {
                let mut queue = lock(&self.queue);
                queue.slug = Some(slug.to_owned());
                queue.tracks = tracks;
                queue.index = 0;
                queue.preloaded = false;
            }
            {
                let mut now = lock(&self.now);
                now.status = Status::Loading;
                now.place(at);
                now.duration = track.duration;
                now.wants = playing;
            }
            log::info!("session: pongo {id} desde {at:?}");
            let loaded = match playing {
                true => player.load(&id, at, false),
                false => player.load_paused_at(&id, at),
            };
            return loaded.map_err(failed);
        }

        let upcoming: Vec<TrackItem> = state.upcoming.iter().filter_map(item).collect();
        {
            let mut queue = lock(&self.queue);
            let keep = (queue.index + 1).min(queue.tracks.len());
            let differs = queue.tracks[keep..]
                .iter()
                .map(|track| track.id.as_str())
                .ne(upcoming.iter().map(|track| track.id.as_str()));
            if differs {
                queue.tracks.truncate(keep);
                queue.tracks.extend(upcoming);
                queue.preloaded = false;
            }
        }
        let Some(player) = self.player() else {
            return Ok(());
        };
        let (toggled, live, loading) = {
            let mut now = lock(&self.now);
            let toggled = now.wants != playing;
            now.wants = playing;
            (toggled, now.live(), now.status == Status::Loading)
        };
        if toggled {
            match playing {
                true => player.play(),
                false => player.pause(),
            }
        }
        let tolerance = match toggled || !playing {
            true => NUDGE,
            false => DRIFT,
        };
        if !loading && live.abs_diff(at) > tolerance {
            lock(&self.now).place(at);
            player.seek(at);
        }
        Ok(())
    }

    fn mark(&self) -> Mark {
        let queue = lock(&self.queue);
        let playing = lock(&self.now).wants;
        Mark {
            track: queue.tracks.get(queue.index).map(|track| track.id.clone()),
            playing,
            upcoming: queue
                .tracks
                .iter()
                .skip(queue.index + 1)
                .take(UPCOMING)
                .map(|track| track.id.clone())
                .collect(),
        }
    }

    /// Cuenta a la sesión lo que cambió aquí: otra canción, play o pausa, un salto o la cola.
    pub(super) fn publish(&self) {
        let mut listening = lock(&self.listening);
        if listening.room.is_none()
            || listening.asking
            || listening.remote
            || listening.applying
            || listening.link.is_none()
        {
            return;
        }
        let mark = self.mark();
        let (seeks, ends, loading, wants, live) = {
            let now = lock(&self.now);
            (
                now.seeks,
                now.ends,
                now.status == Status::Loading,
                now.wants,
                now.live(),
            )
        };
        let hosting = listening.room.as_ref().is_some_and(Room::hosting);
        // una canción que termina sola hace avanzar a todos, y quien inició la sesión dice dónde
        let ended = ends != listening.ends;
        if ended && !hosting {
            if !loading {
                listening.baseline = Some(mark);
                listening.seeks = seeks;
                listening.ends = ends;
            }
            return;
        }
        let started = listening.starting && !loading;
        let same = listening.baseline.as_ref() == Some(&mark) && seeks == listening.seeks;
        if (same && !ended && !started) || (ended && loading) {
            return;
        }
        let mut state = {
            let queue = lock(&self.queue);
            Shared {
                track: queue.tracks.get(queue.index).map(full),
                upcoming: queue
                    .tracks
                    .iter()
                    .skip(queue.index + 1)
                    .take(UPCOMING)
                    .map(full)
                    .collect(),
                playing: wants,
                position: live.as_millis() as u64,
                at: 0,
            }
        };
        // mientras carga no suena nada: los demás la cargan en pausa y siguen cuando aquí suene
        listening.starting = loading && state.playing;
        state.playing &= !loading;
        let lead = match state.playing {
            true => listening.one_way(),
            false => 0,
        };
        listening.sent += 1;
        let seq = listening.sent;
        if let Some(link) = &listening.link {
            let _ = link.send(Request::State {
                state: Box::new(Shared {
                    position: state.position + lead,
                    ..state.clone()
                }),
                seq,
            });
        }
        let at = listening.server_now();
        listening.shared = Some(Shared { at, ..state });
        listening.baseline = Some(mark);
        listening.seeks = seeks;
        listening.ends = ends;
    }

    // ── Modo solo control ───────────────────────────────────────────────────────────────────

    pub(super) fn remote(&self) -> bool {
        let listening = lock(&self.listening);
        listening.remote && listening.room.is_some()
    }

    /// Cambia lo que suena en la sesión sin sonar aquí.
    pub(super) fn remote_change(&self, change: impl FnOnce(&mut Shared)) {
        {
            let mut listening = lock(&self.listening);
            if listening.link.is_none() {
                return;
            }
            let mut state = listening.shared.clone().unwrap_or_default();
            state.position = listening.expected(&state).as_millis() as u64;
            change(&mut state);
            let lead = match state.playing {
                true => listening.one_way(),
                false => 0,
            };
            listening.sent += 1;
            let seq = listening.sent;
            if let Some(link) = &listening.link {
                let _ = link.send(Request::State {
                    state: Box::new(Shared {
                        position: state.position + lead,
                        at: 0,
                        ..state.clone()
                    }),
                    seq,
                });
            }
            let at = listening.server_now();
            listening.shared = Some(Shared { at, ..state });
        }
        self.notify();
    }

    pub(super) fn remote_play(
        &self,
        slug: &str,
        tracks: &[TrackItem],
        index: usize,
    ) -> Result<(), MobileError> {
        let provider = lock(&self.listening)
            .room
            .as_ref()
            .map(|room| room.provider.clone())
            .unwrap_or_default();
        if provider != slug {
            return Err(message(&format!(
                "La sesión suena desde {provider}: busca la canción en ese servicio."
            )));
        }
        let Some(chosen) = tracks.get(index) else {
            return Err(message("La canción elegida no está en la lista."));
        };
        let track = full(chosen);
        let upcoming: Vec<Track> = tracks[index + 1..]
            .iter()
            .take(UPCOMING)
            .map(full)
            .collect();
        self.remote_change(move |state| {
            state.track = Some(track);
            state.upcoming = upcoming;
            state.playing = true;
            state.position = 0;
        });
        Ok(())
    }

    /// Lo que suena en la sesión, para pintarlo como si sonara aquí.
    pub(super) fn remote_state(&self) -> Option<PlaybackState> {
        let (provider, state, position) = {
            let listening = lock(&self.listening);
            if !listening.remote || listening.room.is_none() {
                return None;
            }
            let provider = listening.room.as_ref().map(|room| room.provider.clone());
            let state = listening.shared.clone().unwrap_or_default();
            let position = listening.expected(&state);
            (provider, state, position)
        };
        let track = state.track.as_ref().and_then(item);
        let status = match (&track, state.playing) {
            (None, _) => Status::Idle,
            (Some(_), true) => Status::Playing,
            (Some(_), false) => Status::Paused,
        };
        let mut state = PlaybackState {
            status,
            provider,
            index: 0,
            queue_length: track.is_some() as u32 + state.upcoming.len() as u32,
            position_ms: position.as_millis() as u64,
            duration_ms: track.as_ref().map_or(0, |track| track.duration_ms),
            track,
            volume: *lock(&self.level),
            remote: true,
            cover_large: None,
        };
        state.cover_large = self.large_cover(&state);
        Some(state)
    }

    pub(super) fn remote_queue(&self) -> Option<Vec<TrackItem>> {
        let state = {
            let listening = lock(&self.listening);
            if !listening.remote || listening.room.is_none() {
                return None;
            }
            listening.shared.clone().unwrap_or_default()
        };
        Some(
            state
                .track
                .iter()
                .chain(state.upcoming.iter())
                .filter_map(item)
                .collect(),
        )
    }
}

// ── La conexión ─────────────────────────────────────────────────────────────────────────────

enum Heard {
    Reply(Box<Reply>),
    /// No se pudo conectar o se cayó la conexión. No llega nada más después.
    Lost,
}

/// El reloj de esta app en milisegundos desde 1970, que avanza con el reloj monótono para que un
/// ajuste de hora no mueva las diferencias medidas con el servidor.
fn clock() -> u64 {
    static ORIGIN: OnceLock<(Instant, u64)> = OnceLock::new();
    let (start, epoch) = ORIGIN.get_or_init(|| {
        let epoch = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |since| since.as_millis() as u64);
        (Instant::now(), epoch)
    });
    epoch + start.elapsed().as_millis() as u64
}

async fn run(
    url: String,
    first: Request,
    mut outgoing: UnboundedReceiver<Request>,
    heard: UnboundedSender<Heard>,
) {
    let socket = match tokio::time::timeout(CONNECT, tokio_tungstenite::connect_async(&url)).await {
        Ok(Ok((socket, _))) => socket,
        Ok(Err(error)) => {
            log::warn!("session: no se pudo llegar a {url}: {error}");
            let _ = heard.send(Heard::Lost);
            return;
        }
        Err(_) => {
            log::warn!("session: {url} no respondió a tiempo");
            let _ = heard.send(Heard::Lost);
            return;
        }
    };
    let (mut sink, mut stream) = socket.split();
    let mut ping = tokio::time::interval(BURST_GAP);
    let mut pinged = 0;
    let mut pending = Some(first);

    loop {
        let request = match pending.take() {
            Some(request) => Some(request),
            None => tokio::select! {
                request = outgoing.recv() => match request {
                    Some(request) => Some(request),
                    None => {
                        let _ = sink.close().await;
                        return;
                    }
                },
                message = stream.next() => {
                    match message {
                        Some(Ok(Message::Text(text))) => match serde_json::from_str(&text) {
                            Ok(reply) => {
                                let _ = heard.send(Heard::Reply(Box::new(reply)));
                            }
                            Err(error) => log::warn!("session: respuesta ilegible: {error}"),
                        },
                        Some(Ok(Message::Close(_))) | Some(Err(_)) | None => {
                            let _ = heard.send(Heard::Lost);
                            return;
                        }
                        Some(Ok(_)) => {}
                    }
                    None
                }
                _ = ping.tick() => Some(Request::Ping { t: clock() }),
            },
        };
        let Some(request) = request else {
            continue;
        };
        if matches!(request, Request::Ping { .. }) {
            pinged += 1;
            if pinged == BURST {
                ping = tokio::time::interval_at(tokio::time::Instant::now() + PING, PING);
            }
        }
        let text = match serde_json::to_string(&request) {
            Ok(text) => text,
            Err(error) => {
                log::error!("session: no se pudo codificar una petición: {error}");
                continue;
            }
        };
        if let Err(error) = sink.send(Message::Text(text.into())).await {
            log::warn!("session: no se pudo enviar al servidor: {error}");
            let _ = heard.send(Heard::Lost);
            return;
        }
    }
}

fn message(text: &str) -> MobileError {
    MobileError::Failed {
        message: text.to_owned(),
    }
}
