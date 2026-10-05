use std::path::Path;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use tokio::net::TcpListener;
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static LISTENER: Mutex<Option<Arc<TcpListener>>> = Mutex::new(None);

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

fn fail(env: &mut JNIEnv, msg: impl std::fmt::Display) {
    let _ = env.throw_new("java/io/IOException", msg.to_string());
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(String::from)
}

fn to_jstring(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_localIp(mut env: JNIEnv, _class: JClass) -> jstring {
    let ip = flux_core::local_ip().map(|ip| ip.to_string()).unwrap_or_default();
    to_jstring(&mut env, ip)
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_bindReceiver(mut env: JNIEnv, _class: JClass) -> jint {
    match runtime().block_on(TcpListener::bind("0.0.0.0:0")) {
        Ok(listener) => {
            let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
            *LISTENER.lock().unwrap() = Some(Arc::new(listener));
            port as jint
        }
        Err(e) => {
            fail(&mut env, e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_receiveOne(
    mut env: JNIEnv,
    _class: JClass,
    out_dir: JString,
) -> jstring {
    let Some(dir) = read_string(&mut env, &out_dir) else {
        return ptr::null_mut();
    };
    let listener = LISTENER.lock().unwrap().clone();
    let Some(listener) = listener else {
        fail(&mut env, "receiver is not bound");
        return ptr::null_mut();
    };
    match runtime().block_on(flux_core::receive_one(&listener, Path::new(&dir))) {
        Ok(saved) => to_jstring(&mut env, saved.to_string_lossy().into_owned()),
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_sendFile(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
    path: JString,
) -> jlong {
    let Some(addr) = read_string(&mut env, &addr) else {
        return 0;
    };
    let Some(path) = read_string(&mut env, &path) else {
        return 0;
    };
    match runtime().block_on(flux_core::send_file(addr, Path::new(&path))) {
        Ok(bytes) => bytes as jlong,
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            0
        }
    }
}
