//! El token de origen de YouTube (PO token), igual que en la app de escritorio
//! (`state/src/potoken.rs`): el cliente de YouTube anota para qué identificador necesita un
//! token, la app abre una página de YouTube en una vista web oculta con `potoken.js`, y la página
//! deja el token en una cookie. Aquí solo están las piezas; la vista web vive en Swift.

/// La página que acuña los tokens: el reproductor incrustado del primer video de YouTube.
const PAGE: &str = "https://www.youtube.com/embed/jNQXAC9IVRw";
/// La cookie donde la página deja sus respuestas.
const COOKIE: &str = "SONORA_POT";
/// Con qué empieza una respuesta fallida. Un token es base64url y nunca empieza así.
const FAILED: char = '!';
const SEPARATOR: char = ':';

/// Lo que respondió la página para un identificador.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PoTokenReply {
    Minted { token: String },
    Failed { reason: String },
}

/// El identificador para el que el cliente espera un token, si hay alguno. Se consume.
#[uniffi::export]
pub fn potoken_wanted() -> Option<String> {
    music::potoken::wanted()
}

/// La dirección de la página que acuña el token para `binding`.
#[uniffi::export]
pub fn potoken_page(binding: String) -> String {
    format!("{PAGE}?binding={}", music::escape::component(&binding))
}

/// El script que se inyecta en la página al empezar a cargar.
#[uniffi::export]
pub fn potoken_script() -> String {
    include_str!("../../state/src/potoken.js").to_owned()
}

/// El nombre de la cookie que se vigila.
#[uniffi::export]
pub fn potoken_cookie() -> String {
    COOKIE.to_owned()
}

/// Lee la respuesta para `binding` del valor de la cookie, o nada mientras siga la de una
/// petición anterior.
#[uniffi::export]
pub fn potoken_reply(cookie: String, binding: String) -> Option<PoTokenReply> {
    let prefix = format!("{}{SEPARATOR}", music::escape::component(&binding));
    let answer = cookie.strip_prefix(prefix.as_str())?;
    Some(match answer.strip_prefix(FAILED) {
        Some(reason) => PoTokenReply::Failed {
            reason: reason.to_owned(),
        },
        None => PoTokenReply::Minted {
            token: answer.to_owned(),
        },
    })
}

/// Guarda un token acuñado.
#[uniffi::export]
pub fn potoken_give(binding: String, token: String) {
    music::potoken::give(&binding, token);
    music::potoken::set_warm(true);
}

/// Anota que el intento falló; el cliente espera un poco antes de pedir otro.
#[uniffi::export]
pub fn potoken_failed() {
    music::potoken::failed();
    music::potoken::set_warm(false);
}

/// Anota que la página se cerró.
#[uniffi::export]
pub fn potoken_closed() {
    music::potoken::set_warm(false);
}
