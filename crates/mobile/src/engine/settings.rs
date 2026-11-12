//! Ajustes de audio: calidad del stream, normalización de volumen, paso sin cortes y
//! ecualizador de diez bandas, los mismos de la app de escritorio. La app los guarda y los
//! vuelve a pasar al abrir.

use std::sync::Arc;

use music::Quality;
use music::equalizer::{Curve, Edit, Preset};

use super::{Engine, Inner, lock};

/// Cuántas bandas tiene el ecualizador gráfico.
const BANDS: usize = 10;

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AudioQuality {
    Low,
    Normal,
    High,
    Lossless,
    HiRes,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AudioSettings {
    pub quality: AudioQuality,
    /// Igualar el volumen entre canciones.
    pub normalisation: bool,
    /// Pasar a la siguiente sin silencio en medio.
    pub gapless: bool,
    pub equalizer: bool,
    /// La ganancia de cada banda en dB, de graves a agudos.
    pub bands: Vec<f64>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct EqualizerPreset {
    pub id: String,
    pub name: String,
    pub gains: Vec<f64>,
}

#[uniffi::export]
impl Engine {
    pub fn audio_settings(&self) -> AudioSettings {
        let config = lock(&self.inner.config);
        let equalizer = lock(&self.inner.equalizer);
        AudioSettings {
            quality: from_quality(config.quality),
            normalisation: config.normalisation,
            gapless: config.gapless,
            equalizer: equalizer.enabled(),
            bands: gains(equalizer.curve()),
        }
    }

    /// Aplica los ajustes. El ecualizador cambia al instante; la calidad, la normalización y el
    /// paso sin cortes reinician el reproductor donde iba.
    pub fn set_audio_settings(&self, settings: AudioSettings) {
        let restart = {
            let mut config = lock(&self.inner.config);
            let quality = to_quality(settings.quality);
            let restart = config.quality != quality
                || config.normalisation != settings.normalisation
                || config.gapless != settings.gapless;
            config.quality = quality;
            config.normalisation = settings.normalisation;
            config.gapless = settings.gapless;
            restart
        };
        {
            let mut bands = settings.bands.clone();
            bands.resize(BANDS, 0.);
            let mut equalizer = lock(&self.inner.equalizer);
            equalizer.set_curve(Curve::bands(&bands), Edit::Glide);
            equalizer.set_enabled(settings.equalizer);
        }
        if restart {
            self.inner.restart_player();
        }
    }

    pub fn equalizer_presets(&self) -> Vec<EqualizerPreset> {
        Preset::ALL
            .into_iter()
            .map(|preset| EqualizerPreset {
                id: preset.id().to_owned(),
                name: preset_name(preset).to_owned(),
                gains: gains(&preset.curve()),
            })
            .collect()
    }
}

impl Inner {
    /// Arranca otro reproductor con la configuración nueva y sigue donde iba.
    fn restart_player(self: &Arc<Self>) {
        let Some(old) = lock(&self.active).take() else {
            return;
        };
        let slug = old.slug.clone();
        old.player.pause();
        drop(old);
        let Some(id) = self.current_id() else {
            return;
        };
        let (at, wants) = {
            let now = lock(&self.now);
            (now.live(), now.wants)
        };
        let player = match self.engine_for(&slug) {
            Ok(player) => player,
            Err(error) => {
                self.listener.on_problem(error.to_string());
                return;
            }
        };
        lock(&self.queue).preloaded = false;
        let loaded = match wants {
            true => player.load(&id, at, false),
            false => player.load_paused_at(&id, at),
        };
        if let Err(error) = loaded {
            log::warn!("mobile: no se pudo retomar {id} con los ajustes nuevos: {error:#}");
        }
    }
}

fn gains(curve: &Curve) -> Vec<f64> {
    let mut gains: Vec<f64> = curve.filters().iter().map(|filter| filter.gain()).collect();
    gains.resize(BANDS, 0.);
    gains
}

fn from_quality(quality: Quality) -> AudioQuality {
    match quality {
        Quality::Low => AudioQuality::Low,
        Quality::Normal => AudioQuality::Normal,
        Quality::High => AudioQuality::High,
        Quality::Lossless => AudioQuality::Lossless,
        Quality::HiRes => AudioQuality::HiRes,
    }
}

fn to_quality(quality: AudioQuality) -> Quality {
    match quality {
        AudioQuality::Low => Quality::Low,
        AudioQuality::Normal => Quality::Normal,
        AudioQuality::High => Quality::High,
        AudioQuality::Lossless => Quality::Lossless,
        AudioQuality::HiRes => Quality::HiRes,
    }
}

fn preset_name(preset: Preset) -> &'static str {
    match preset {
        Preset::Flat => "Plano",
        Preset::BassBoost => "Más graves",
        Preset::BassReducer => "Menos graves",
        Preset::TrebleBoost => "Más agudos",
        Preset::Vocal => "Voces",
        Preset::Rock => "Rock",
        Preset::Pop => "Pop",
        Preset::Jazz => "Jazz",
        Preset::Classical => "Clásica",
        Preset::Electronic => "Electrónica",
        Preset::Acoustic => "Acústica",
        Preset::Loudness => "Sonoridad",
    }
}
