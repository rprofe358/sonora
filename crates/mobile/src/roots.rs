//! Los certificados raíz para las conexiones seguras. Varias librerías (librespot, su cliente de
//! OAuth, los WebSockets) leen "los certificados del sistema" con `rustls-native-certs`, que en
//! iOS no encuentra ninguno y hace fallar el inicio de sesión de Spotify. Esa librería también
//! acepta un archivo PEM en `SSL_CERT_FILE`, así que al arrancar se escribe uno con las raíces de
//! Mozilla (las mismas que usa Firefox) y se apunta ahí.

#[cfg(target_os = "ios")]
pub(crate) fn install() {
    use base64::Engine as _;
    use std::fmt::Write as _;

    if std::env::var_os("SSL_CERT_FILE").is_some() {
        return;
    }
    let Some(home) = std::env::var_os("HOME") else {
        return;
    };
    let folder = std::path::Path::new(&home).join("Library").join("Caches");
    let path = folder.join("sonora-roots.pem");
    let mut pem = String::new();
    for root in webpki_root_certs::TLS_SERVER_ROOT_CERTS {
        let encoded = base64::engine::general_purpose::STANDARD.encode(root.as_ref());
        pem.push_str("-----BEGIN CERTIFICATE-----\n");
        for line in encoded.as_bytes().chunks(64) {
            let _ = writeln!(pem, "{}", String::from_utf8_lossy(line));
        }
        pem.push_str("-----END CERTIFICATE-----\n");
    }
    let written = std::fs::create_dir_all(&folder).and_then(|()| std::fs::write(&path, pem));
    if let Err(error) = written {
        log::warn!("mobile: no se pudieron escribir los certificados raíz: {error}");
        return;
    }
    // Se hace una sola vez, al arrancar el motor y antes de abrir ninguna conexión.
    unsafe { std::env::set_var("SSL_CERT_FILE", &path) };
}

#[cfg(not(target_os = "ios"))]
pub(crate) fn install() {}
