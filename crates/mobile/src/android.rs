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

    use jni::JNIEnv;
    use jni::objects::{JClass, JObject};

    static READY: AtomicBool = AtomicBool::new(false);

    /// `NativeContext.init(context)` en Kotlin: guarda la JavaVM y el Context de la app para
    /// las librerías que los piden (cpal con AAudio).
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_sonora_mobile_NativeContext_init(
        env: JNIEnv,
        _class: JClass,
        context: JObject,
    ) {
        if READY.swap(true, Ordering::SeqCst) {
            return;
        }
        let Ok(vm) = env.get_java_vm() else {
            return;
        };
        let Ok(global) = env.new_global_ref(context) else {
            return;
        };
        let raw = global.as_obj().as_raw();
        // la referencia global vive mientras viva la app
        std::mem::forget(global);
        unsafe {
            ndk_context::initialize_android_context(vm.get_java_vm_pointer().cast(), raw.cast());
        }
    }
}
