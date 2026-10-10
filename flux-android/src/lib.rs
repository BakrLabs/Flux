use std::os::fd::FromRawFd;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

use flux_core::{Control, Identity, RecvSession, SendSession};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use tokio::net::TcpListener;
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static LISTENER: Mutex<Option<Arc<TcpListener>>> = Mutex::new(None);
static IDENTITY: Mutex<Option<Identity>> = Mutex::new(None);
static OUTGOING: Mutex<Option<SendSession>> = Mutex::new(None);
static INCOMING: Mutex<Option<RecvSession>> = Mutex::new(None);
static SEND: Control = Control::new();
static RECEIVE: Control = Control::new();

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

fn control(channel: jint) -> &'static Control {
    if channel == 0 {
        &SEND
    } else {
        &RECEIVE
    }
}

fn identity() -> Option<Identity> {
    IDENTITY.lock().unwrap().clone()
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
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_generateKey(mut env: JNIEnv, _class: JClass) -> jstring {
    match Identity::generate_key() {
        Ok(key) => to_jstring(&mut env, key),
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_setIdentity(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
    name: JString,
) {
    let Some(key) = read_string(&mut env, &key) else {
        return;
    };
    let Some(name) = read_string(&mut env, &name) else {
        return;
    };
    match Identity::new(&key, &name) {
        Ok(identity) => *IDENTITY.lock().unwrap() = Some(identity),
        Err(e) => fail(&mut env, format!("{e:#}")),
    }
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
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_receiveAccept(mut env: JNIEnv, _class: JClass) -> jstring {
    let listener = LISTENER.lock().unwrap().clone();
    let Some(listener) = listener else {
        fail(&mut env, "receiver is not bound");
        return ptr::null_mut();
    };
    let Some(identity) = identity() else {
        fail(&mut env, "identity is not set");
        return ptr::null_mut();
    };
    match runtime().block_on(flux_core::accept(&listener, &identity, &RECEIVE)) {
        Ok(session) => {
            let peer = &session.peer;
            let info = format!("{}\t{}\t{}", peer.fingerprint, peer.code, peer.name.replace('\t', " "));
            *INCOMING.lock().unwrap() = Some(session);
            to_jstring(&mut env, info)
        }
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_receiveHello(mut env: JNIEnv, _class: JClass) -> jstring {
    let pending = INCOMING.lock().unwrap().take();
    let Some(mut session) = pending else {
        fail(&mut env, "no incoming connection");
        return ptr::null_mut();
    };
    match runtime().block_on(session.hello(&RECEIVE)) {
        Ok((name, size)) => {
            *INCOMING.lock().unwrap() = Some(session);
            to_jstring(&mut env, format!("{}\t{}", name.replace('\t', " "), size))
        }
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_receiveBody(mut env: JNIEnv, _class: JClass, fd: jint) -> jlong {
    let file = unsafe { std::fs::File::from_raw_fd(fd) };
    let pending = INCOMING.lock().unwrap().take();
    let Some(session) = pending else {
        fail(&mut env, "no incoming transfer");
        return 0;
    };
    let mut file = tokio::fs::File::from_std(file);
    let result = runtime().block_on(async {
        session
            .save_to(&mut file, &RECEIVE)
            .await
            .map_err(|e| format!("{e:#}"))?;
        file.sync_all().await.map_err(|e| e.to_string())
    });
    match result {
        Ok(()) => RECEIVE.total() as jlong,
        Err(msg) => {
            fail(&mut env, msg);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_receiveReject(_env: JNIEnv, _class: JClass, code: jint) {
    let pending = INCOMING.lock().unwrap().take();
    if let Some(session) = pending {
        runtime().block_on(session.reject(code as u8));
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_senderConnect(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
) -> jstring {
    let Some(addr) = read_string(&mut env, &addr) else {
        return ptr::null_mut();
    };
    let Some(identity) = identity() else {
        fail(&mut env, "identity is not set");
        return ptr::null_mut();
    };
    match runtime().block_on(flux_core::connect(addr, &identity, &SEND)) {
        Ok(session) => {
            let peer = &session.peer;
            let info = format!("{}\t{}\t{}", peer.fingerprint, peer.code, peer.name.replace('\t', " "));
            *OUTGOING.lock().unwrap() = Some(session);
            to_jstring(&mut env, info)
        }
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_senderAbort(_env: JNIEnv, _class: JClass) {
    OUTGOING.lock().unwrap().take();
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_sendFd(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    size: jlong,
    fd: jint,
) -> jlong {
    let file = unsafe { std::fs::File::from_raw_fd(fd) };
    let Some(name) = read_string(&mut env, &name) else {
        return 0;
    };
    let pending = OUTGOING.lock().unwrap().take();
    let Some(session) = pending else {
        fail(&mut env, "not connected");
        return 0;
    };
    let reader = tokio::fs::File::from_std(file);
    match runtime().block_on(session.send_stream(&name, size as u64, reader, &SEND)) {
        Ok(bytes) => bytes as jlong,
        Err(e) => {
            fail(&mut env, format!("{e:#}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_progressDone(_env: JNIEnv, _class: JClass, channel: jint) -> jlong {
    control(channel).done() as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_progressTotal(_env: JNIEnv, _class: JClass, channel: jint) -> jlong {
    control(channel).total() as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_progressName(mut env: JNIEnv, _class: JClass, channel: jint) -> jstring {
    to_jstring(&mut env, control(channel).name())
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_cancel(_env: JNIEnv, _class: JClass, channel: jint) {
    control(channel).cancel();
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_setPaused(_env: JNIEnv, _class: JClass, channel: jint, paused: jboolean) {
    control(channel).set_paused(paused != 0);
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_flux_FluxCore_reset(_env: JNIEnv, _class: JClass, channel: jint) {
    control(channel).reset();
}
