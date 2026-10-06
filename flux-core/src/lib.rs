use std::net::{IpAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, ToSocketAddrs};
use tokio::time::{sleep, timeout};

const CHUNK: usize = 256 * 1024;
const MAX_NAME: usize = 1024;
const POLL: Duration = Duration::from_millis(300);

pub struct Control {
    done: AtomicU64,
    total: AtomicU64,
    cancelled: AtomicBool,
    paused: AtomicBool,
    name: Mutex<String>,
}

impl Control {
    pub const fn new() -> Self {
        Self {
            done: AtomicU64::new(0),
            total: AtomicU64::new(0),
            cancelled: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            name: Mutex::new(String::new()),
        }
    }

    pub fn done(&self) -> u64 {
        self.done.load(Ordering::Relaxed)
    }

    pub fn total(&self) -> u64 {
        self.total.load(Ordering::Relaxed)
    }

    pub fn name(&self) -> String {
        self.name.lock().unwrap().clone()
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub fn set_paused(&self, paused: bool) {
        self.paused.store(paused, Ordering::Relaxed);
    }

    pub fn reset(&self) {
        self.done.store(0, Ordering::Relaxed);
        self.total.store(0, Ordering::Relaxed);
        self.cancelled.store(false, Ordering::Relaxed);
        self.paused.store(false, Ordering::Relaxed);
        self.name.lock().unwrap().clear();
    }

    fn begin(&self, name: &str, total: u64) {
        *self.name.lock().unwrap() = name.to_string();
        self.total.store(total, Ordering::Relaxed);
        self.done.store(0, Ordering::Relaxed);
    }

    fn advance(&self, bytes: u64) {
        self.done.fetch_add(bytes, Ordering::Relaxed);
    }

    fn check(&self) -> Result<()> {
        if self.cancelled.load(Ordering::Relaxed) {
            bail!("cancelled");
        }
        Ok(())
    }

    async fn checkpoint(&self) -> Result<()> {
        while self.paused.load(Ordering::Relaxed) && !self.cancelled.load(Ordering::Relaxed) {
            sleep(Duration::from_millis(100)).await;
        }
        self.check()
    }
}

impl Default for Control {
    fn default() -> Self {
        Self::new()
    }
}

pub fn local_ip() -> Option<IpAddr> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    Some(sock.local_addr().ok()?.ip())
}

pub async fn send_file<A: ToSocketAddrs>(addr: A, path: &Path) -> Result<u64> {
    send_file_with(addr, path, &Control::new()).await
}

pub async fn receive_one(listener: &TcpListener, out_dir: &Path) -> Result<PathBuf> {
    receive_one_with(listener, out_dir, &Control::new()).await
}

pub async fn send_file_with<A: ToSocketAddrs>(addr: A, path: &Path, ctl: &Control) -> Result<u64> {
    let name = path
        .file_name()
        .context("path has no file name")?
        .to_string_lossy()
        .into_owned();
    if name.len() > MAX_NAME {
        bail!("file name too long");
    }

    let mut file = File::open(path).await?;
    let size = file.metadata().await?.len();
    ctl.begin(&name, size);
    ctl.check()?;

    let mut stream = TcpStream::connect(addr).await?;
    stream.set_nodelay(true)?;
    stream.write_u16(name.len() as u16).await?;
    stream.write_all(name.as_bytes()).await?;
    stream.write_u64(size).await?;

    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    loop {
        ctl.checkpoint().await?;
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        stream.write_all(&buf[..n]).await?;
        ctl.advance(n as u64);
    }
    stream.write_all(hasher.finalize().as_bytes()).await?;
    stream.flush().await?;
    Ok(size)
}

pub async fn receive_one_with(listener: &TcpListener, out_dir: &Path, ctl: &Control) -> Result<PathBuf> {
    let mut stream = loop {
        ctl.check()?;
        if let Ok(accepted) = timeout(POLL, listener.accept()).await {
            break accepted?.0;
        }
    };

    let name_len = stream.read_u16().await? as usize;
    if name_len == 0 || name_len > MAX_NAME {
        bail!("bad file name length");
    }
    let mut name_buf = vec![0u8; name_len];
    stream.read_exact(&mut name_buf).await?;
    let raw_name = String::from_utf8(name_buf)?;
    let name = Path::new(&raw_name)
        .file_name()
        .context("bad file name")?
        .to_owned();
    let size = stream.read_u64().await?;
    ctl.begin(&name.to_string_lossy(), size);

    let dest = out_dir.join(&name);
    let outcome = receive_body(&mut stream, &dest, size, ctl).await;
    if outcome.is_err() {
        tokio::fs::remove_file(&dest).await.ok();
    }
    outcome?;
    Ok(dest)
}

async fn receive_body(stream: &mut TcpStream, dest: &Path, size: u64, ctl: &Control) -> Result<()> {
    let mut file = File::create(dest).await?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    let mut remaining = size;

    while remaining > 0 {
        ctl.checkpoint().await?;
        let want = remaining.min(CHUNK as u64) as usize;
        let n = match timeout(POLL, stream.read(&mut buf[..want])).await {
            Ok(read) => read?,
            Err(_) => continue,
        };
        if n == 0 {
            bail!("connection closed early");
        }
        hasher.update(&buf[..n]);
        file.write_all(&buf[..n]).await?;
        remaining -= n as u64;
        ctl.advance(n as u64);
    }

    let mut expected = [0u8; 32];
    stream.read_exact(&mut expected).await?;
    file.flush().await?;

    if hasher.finalize() != expected {
        bail!("checksum mismatch");
    }
    Ok(())
}
