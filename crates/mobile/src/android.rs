//! Lo que Android necesita antes de arrancar el motor: el contexto de la app para cpal (que
//! habla con AAudio a través de Java) y carpetas donde escribir, porque Android no define HOME.

/// Le dice al motor dónde puede escribir: `home` para cuentas y caché, `temp` para temporales.
/// En Android hay que llamarlo antes que nada; en iOS no hace falta.
#[uniffi::export]
pub fn prepare(home: String, temp: String) {
    // Se hace una sola vez, al abrir la app y antes de arrancar ningún hilo del motor.
    unsafe {
        if std::env::var_os("HOME").is_none() || cfg!(target_os = "android") {
            std::env::set_var("HOME", &home);
        }
        if cfg!(target_os = "android") {
            std::env::set_var("TMPDIR", &temp);
        }
    }
}

#[cfg(target_os = "android")]
mod context {
    use std::sync::atomic::{AtomicBool, Ordering};

    use jni::EnvUnowned;
    use jni::objects::{JClass, JObject};
    use jni::refs::Reference as _;

    static READY: AtomicBool = AtomicBool::new(false);

    /// `NativeContext.init(context)` en Kotlin: guarda la JavaVM y el Context de la app para
    /// las librerías que los piden (cpal con AAudio, y el verificador de certificados de rustls,
    /// que usa el de Android).
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_sonora_mobile_NativeContext_init<'caller>(
        mut unowned: EnvUnowned<'caller>,
        _class: JClass<'caller>,
        context: JObject<'caller>,
    ) {
        if READY.swap(true, Ordering::SeqCst) {
            return;
        }
        let outcome = unowned.with_env(|env| -> Result<(), jni::errors::Error> {
            let vm = env.get_java_vm()?;
            let global = env.new_global_ref(&context)?;
            let raw = global.as_raw();
            // la referencia global vive mientras viva la app
            std::mem::forget(global);
            unsafe {
                ndk_context::initialize_android_context(vm.get_raw().cast(), raw.cast());
            }
            rustls_platform_verifier::android::init_with_env(env, context)?;
            Ok(())
        });
        match outcome.into_outcome() {
            jni::Outcome::Ok(()) => {}
            jni::Outcome::Err(error) => log::warn!("android: no se pudo preparar el motor: {error}"),
            jni::Outcome::Panic(_) => log::warn!("android: el motor falló al prepararse"),
        }
    }
}
