use std::net::{IpAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use tokio::fs::File;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, ToSocketAddrs};
use tokio::time::{sleep, timeout};

const CHUNK: usize = 256 * 1024;
const MAX_NAME: usize = 1024;
const POLL: Duration = Duration::from_millis(300);
const HEADER_TIMEOUT: Duration = Duration::from_secs(10);
const READY_TIMEOUT: Duration = Duration::from_secs(60);
const ACK_TIMEOUT: Duration = Duration::from_secs(120);
const PROTOCOL: u8 = 2;

pub const READY: u8 = 10;
pub const ACK_OK: u8 = 1;
pub const ACK_CORRUPT: u8 = 2;
pub const ACK_FAILED: u8 = 3;
pub const ACK_NO_SPACE: u8 = 4;

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

fn explain(code: u8) -> anyhow::Error {
    match code {
        ACK_CORRUPT => anyhow!("the file arrived damaged (checksum mismatch)"),
        ACK_NO_SPACE => anyhow!("the receiver does not have enough free space"),
        _ => anyhow!("the receiver could not save the file"),
    }
}

async fn read_reply(stream: &mut TcpStream, ctl: &Control, limit: Duration, closed: &str) -> Result<u8> {
    let started = Instant::now();
    let mut byte = [0u8; 1];
    loop {
        ctl.check()?;
        if started.elapsed() > limit {
            bail!("the receiver did not answer in time");
        }
        match timeout(POLL, stream.read(&mut byte)).await {
            Err(_) => continue,
            Ok(Err(e)) => return Err(e.into()),
            Ok(Ok(0)) => bail!("{closed}"),
            Ok(Ok(_)) => return Ok(byte[0]),
        }
    }
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
    let file = File::open(path).await?;
    let size = file.metadata().await?.len();
    send_stream_with(addr, &name, size, file, ctl).await
}

pub async fn send_stream_with<A, R>(addr: A, name: &str, size: u64, mut reader: R, ctl: &Control) -> Result<u64>
where
    A: ToSocketAddrs,
    R: AsyncRead + Unpin,
{
    if name.is_empty() || name.len() > MAX_NAME {
        bail!("bad file name");
    }
    ctl.begin(name, size);
    ctl.check()?;

    let mut stream = TcpStream::connect(addr).await?;
    stream.set_nodelay(true)?;
    stream.write_all(b"FX").await?;
    stream.write_u8(PROTOCOL).await?;
    stream.write_u16(name.len() as u16).await?;
    stream.write_all(name.as_bytes()).await?;
    stream.write_u64(size).await?;
    stream.flush().await?;

    let closed = "the receiver closed the connection (it may be running an older Flux)";
    let reply = read_reply(&mut stream, ctl, READY_TIMEOUT, closed).await?;
    if reply != READY {
        return Err(explain(reply));
    }

    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    loop {
        ctl.checkpoint().await?;
        let n = reader.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        stream.write_all(&buf[..n]).await?;
        ctl.advance(n as u64);
    }
    stream.write_all(hasher.finalize().as_bytes()).await?;
    stream.flush().await?;

    let closed = "the receiver closed the connection before confirming";
    let reply = read_reply(&mut stream, ctl, ACK_TIMEOUT, closed).await?;
    if reply != ACK_OK {
        return Err(explain(reply));
    }
    Ok(size)
}

pub struct Incoming {
    stream: TcpStream,
    pub name: String,
    pub size: u64,
}

async fn read_header(stream: &mut TcpStream) -> Result<(String, u64)> {
    let mut magic = [0u8; 3];
    stream.read_exact(&mut magic).await?;
    if &magic[..2] != b"FX" {
        bail!("the sender is running an older version of Flux");
    }
    if magic[2] != PROTOCOL {
        bail!("the sender uses a different Flux protocol (version {})", magic[2]);
    }
    let name_len = stream.read_u16().await? as usize;
    if name_len == 0 || name_len > MAX_NAME {
        bail!("bad file name length");
    }
    let mut buf = vec![0u8; name_len];
    stream.read_exact(&mut buf).await?;
    let raw = String::from_utf8(buf)?;
    let name = Path::new(&raw)
        .file_name()
        .context("bad file name")?
        .to_string_lossy()
        .into_owned();
    let size = stream.read_u64().await?;
    Ok((name, size))
}

pub async fn accept_incoming(listener: &TcpListener, ctl: &Control) -> Result<Incoming> {
    let mut stream = loop {
        ctl.check()?;
        if let Ok(accepted) = timeout(POLL, listener.accept()).await {
            break accepted?.0;
        }
    };
    let (name, size) = timeout(HEADER_TIMEOUT, read_header(&mut stream))
        .await
        .map_err(|_| anyhow!("the sender stalled while introducing the file"))??;
    ctl.begin(&name, size);
    Ok(Incoming { stream, name, size })
}

impl Incoming {
    pub async fn save_to<W: AsyncWrite + Unpin>(mut self, writer: &mut W, ctl: &Control) -> Result<()> {
        self.stream.write_all(&[READY]).await?;
        self.stream.flush().await?;

        let outcome = receive_body(&mut self.stream, writer, self.size, ctl).await;
        let code = match &outcome {
            Ok(true) => ACK_OK,
            Ok(false) => ACK_CORRUPT,
            Err(_) => ACK_FAILED,
        };
        let _ = self.stream.write_all(&[code]).await;
        let _ = self.stream.flush().await;

        match outcome {
            Ok(true) => Ok(()),
            Ok(false) => bail!("checksum mismatch"),
            Err(e) => Err(e),
        }
    }

    pub async fn reject(mut self, code: u8) {
        let _ = self.stream.write_all(&[code]).await;
        let _ = self.stream.flush().await;
    }
}

pub async fn receive_one_with(listener: &TcpListener, out_dir: &Path, ctl: &Control) -> Result<PathBuf> {
    let incoming = accept_incoming(listener, ctl).await?;
    let dest = out_dir.join(&incoming.name);
    let mut file = File::create(&dest).await?;
    let outcome = incoming.save_to(&mut file, ctl).await;
    drop(file);
    if outcome.is_err() {
        tokio::fs::remove_file(&dest).await.ok();
    }
    outcome?;
    Ok(dest)
}

async fn receive_body<W: AsyncWrite + Unpin>(
    stream: &mut TcpStream,
    writer: &mut W,
    size: u64,
    ctl: &Control,
) -> Result<bool> {
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
        writer.write_all(&buf[..n]).await?;
        remaining -= n as u64;
        ctl.advance(n as u64);
    }

    let mut expected = [0u8; 32];
    let mut got = 0;
    while got < expected.len() {
        ctl.check()?;
        match timeout(POLL, stream.read(&mut expected[got..])).await {
            Err(_) => continue,
            Ok(read) => {
                let n = read?;
                if n == 0 {
                    bail!("connection closed early");
                }
                got += n;
            }
        }
    }
    writer.flush().await?;
    Ok(hasher.finalize() == expected)
}
