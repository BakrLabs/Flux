use std::net::{IpAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use snow::params::NoiseParams;
use snow::{Builder, TransportState};
use tokio::fs::File;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, ToSocketAddrs};
use tokio::time::{sleep, timeout};

const PATTERN: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
const PROLOGUE: &[u8] = b"flux/3";
const MAGIC: &[u8; 4] = b"FLX3";
const PLAIN: usize = 48 * 1024;
const MAX_NAME: usize = 1024;
const POLL: Duration = Duration::from_millis(300);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(20);
const HUMAN_TIMEOUT: Duration = Duration::from_secs(180);
const IDLE_TIMEOUT: Duration = Duration::from_secs(900);
const ACK_TIMEOUT: Duration = Duration::from_secs(120);

const K_HELLO: u8 = 1;
const K_READY: u8 = 2;
const K_REJECT: u8 = 3;
const K_DATA: u8 = 4;
const K_FIN: u8 = 5;
const K_ACK: u8 = 6;

pub const ACK_OK: u8 = 1;
pub const ACK_CORRUPT: u8 = 2;
pub const ACK_FAILED: u8 = 3;
pub const ACK_NO_SPACE: u8 = 4;
pub const ACK_DECLINED: u8 = 5;

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

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn from_hex(text: &str) -> Result<Vec<u8>> {
    if !text.is_ascii() || text.len() % 2 != 0 {
        bail!("bad key encoding");
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16).map_err(|_| anyhow!("bad key encoding")))
        .collect()
}

fn be_u64(bytes: &[u8]) -> Option<u64> {
    let arr: [u8; 8] = bytes.get(..8)?.try_into().ok()?;
    Some(u64::from_be_bytes(arr))
}

fn clean_name(raw: &str) -> String {
    let name: String = raw.chars().filter(|c| !c.is_control()).take(48).collect();
    if name.trim().is_empty() {
        "Unknown device".to_string()
    } else {
        name
    }
}

fn sas(hash: &[u8]) -> String {
    let mut value = 0u64;
    for byte in hash.iter().take(5) {
        value = (value << 8) | *byte as u64;
    }
    let n = value % 1_000_000_000_000;
    format!(
        "{:03} {:03} {:03} {:03}",
        n / 1_000_000_000,
        (n / 1_000_000) % 1000,
        (n / 1000) % 1000,
        n % 1000
    )
}

fn explain(code: u8) -> anyhow::Error {
    match code {
        ACK_CORRUPT => anyhow!("the file arrived damaged"),
        ACK_NO_SPACE => anyhow!("the receiver does not have enough free space"),
        ACK_DECLINED => anyhow!("the other device declined the transfer"),
        _ => anyhow!("the receiver could not save the file"),
    }
}

#[derive(Clone)]
pub struct Identity {
    private: Vec<u8>,
    name: String,
}

impl Identity {
    pub fn generate_key() -> Result<String> {
        let params: NoiseParams = PATTERN.parse()?;
        let keypair = Builder::new(params).generate_keypair()?;
        Ok(to_hex(&keypair.private))
    }

    pub fn new(key_hex: &str, name: &str) -> Result<Self> {
        let private = from_hex(key_hex)?;
        if private.len() != 32 {
            bail!("bad key length");
        }
        Ok(Self {
            private,
            name: clean_name(name),
        })
    }

    pub fn generate(name: &str) -> Result<Self> {
        Self::new(&Self::generate_key()?, name)
    }
}

pub struct Peer {
    pub fingerprint: String,
    pub code: String,
    pub name: String,
}

struct Wire {
    stream: TcpStream,
    rbuf: Vec<u8>,
    scratch: Vec<u8>,
}

impl Wire {
    fn new(stream: TcpStream) -> Self {
        Self {
            stream,
            rbuf: Vec::new(),
            scratch: vec![0u8; 64 * 1024],
        }
    }

    async fn fill(&mut self, need: usize, ctl: &Control, limit: Duration) -> Result<()> {
        let started = Instant::now();
        while self.rbuf.len() < need {
            ctl.check()?;
            if started.elapsed() > limit {
                bail!("timed out waiting for the other device");
            }
            match timeout(POLL, self.stream.read(&mut self.scratch)).await {
                Err(_) => continue,
                Ok(Err(e)) => return Err(e.into()),
                Ok(Ok(0)) => bail!("the other device closed the connection"),
                Ok(Ok(n)) => self.rbuf.extend_from_slice(&self.scratch[..n]),
            }
        }
        Ok(())
    }

    async fn write_frame(&mut self, data: &[u8]) -> Result<()> {
        let len = u16::try_from(data.len()).map_err(|_| anyhow!("frame too large"))?;
        let mut out = Vec::with_capacity(2 + data.len());
        out.extend_from_slice(&len.to_be_bytes());
        out.extend_from_slice(data);
        self.stream.write_all(&out).await?;
        Ok(())
    }

    async fn read_frame(&mut self, ctl: &Control, limit: Duration) -> Result<Vec<u8>> {
        self.fill(2, ctl, limit).await?;
        let len = u16::from_be_bytes([self.rbuf[0], self.rbuf[1]]) as usize;
        self.fill(2 + len, ctl, limit).await?;
        let frame = self.rbuf[2..2 + len].to_vec();
        self.rbuf.drain(..2 + len);
        Ok(frame)
    }
}

struct Channel {
    wire: Wire,
    noise: TransportState,
    plain: Vec<u8>,
    cipher: Vec<u8>,
    inbox: Vec<u8>,
}

impl Channel {
    async fn send(&mut self, kind: u8, payload: &[u8]) -> Result<()> {
        if payload.len() > PLAIN {
            bail!("message too large");
        }
        self.plain.clear();
        self.plain.push(kind);
        self.plain.extend_from_slice(payload);
        let n = self.noise.write_message(&self.plain, &mut self.cipher)?;
        self.wire.write_frame(&self.cipher[..n]).await
    }

    async fn recv(&mut self, ctl: &Control, limit: Duration) -> Result<(u8, Vec<u8>)> {
        let frame = self.wire.read_frame(ctl, limit).await?;
        let n = self.noise.read_message(&frame, &mut self.inbox)?;
        if n == 0 {
            bail!("empty message");
        }
        Ok((self.inbox[0], self.inbox[1..n].to_vec()))
    }
}

async fn handshake(
    stream: TcpStream,
    identity: &Identity,
    initiator: bool,
    ctl: &Control,
) -> Result<(Channel, Peer)> {
    let params: NoiseParams = PATTERN.parse()?;
    let builder = Builder::new(params)
        .local_private_key(&identity.private)
        .prologue(PROLOGUE);
    let mut state = if initiator {
        builder.build_initiator()?
    } else {
        builder.build_responder()?
    };

    let mut wire = Wire::new(stream);
    let mut buf = vec![0u8; 65535];
    let remote_name: Vec<u8>;

    if initiator {
        wire.stream.write_all(MAGIC).await?;
        let n = state.write_message(&[], &mut buf)?;
        wire.write_frame(&buf[..n]).await?;
        let frame = wire
            .read_frame(ctl, HANDSHAKE_TIMEOUT)
            .await
            .map_err(|e| e.context("the other device did not answer (it may run an older Flux)"))?;
        let len = state.read_message(&frame, &mut buf)?;
        remote_name = buf[..len].to_vec();
        let n = state.write_message(identity.name.as_bytes(), &mut buf)?;
        wire.write_frame(&buf[..n]).await?;
    } else {
        wire.fill(MAGIC.len(), ctl, HANDSHAKE_TIMEOUT).await?;
        if &wire.rbuf[..MAGIC.len()] != MAGIC {
            if wire.rbuf.starts_with(b"FX") {
                bail!("the sender is running an older version of Flux");
            }
            bail!("not a Flux connection");
        }
        wire.rbuf.drain(..MAGIC.len());
        let frame = wire.read_frame(ctl, HANDSHAKE_TIMEOUT).await?;
        state.read_message(&frame, &mut buf)?;
        let n = state.write_message(identity.name.as_bytes(), &mut buf)?;
        wire.write_frame(&buf[..n]).await?;
        let frame = wire.read_frame(ctl, HANDSHAKE_TIMEOUT).await?;
        let len = state.read_message(&frame, &mut buf)?;
        remote_name = buf[..len].to_vec();
    }

    let remote_key = state.get_remote_static().context("the other device sent no key")?.to_vec();
    let hash = state.get_handshake_hash().to_vec();
    let noise = state.into_transport_mode()?;

    let peer = Peer {
        fingerprint: to_hex(blake3::hash(&remote_key).as_bytes()),
        code: sas(&hash),
        name: clean_name(&String::from_utf8_lossy(&remote_name)),
    };
    let channel = Channel {
        wire,
        noise,
        plain: Vec::new(),
        cipher: vec![0u8; 65535],
        inbox: vec![0u8; 65535],
    };
    Ok((channel, peer))
}

pub struct SendSession {
    channel: Channel,
    pub peer: Peer,
}

pub async fn connect<A: ToSocketAddrs>(addr: A, identity: &Identity, ctl: &Control) -> Result<SendSession> {
    ctl.check()?;
    let stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| anyhow!("could not reach the other device"))??;
    stream.set_nodelay(true)?;
    let (channel, peer) = handshake(stream, identity, true, ctl).await?;
    Ok(SendSession { channel, peer })
}

impl SendSession {
    pub async fn send_stream<R: AsyncRead + Unpin>(
        mut self,
        name: &str,
        size: u64,
        mut reader: R,
        ctl: &Control,
    ) -> Result<u64> {
        if name.is_empty() || name.len() > MAX_NAME {
            bail!("bad file name");
        }
        ctl.begin(name, size);

        let mut hello = size.to_be_bytes().to_vec();
        hello.extend_from_slice(name.as_bytes());
        if let Err(e) = self.channel.send(K_HELLO, &hello).await {
            if let Ok((K_REJECT, body)) = self.channel.recv(ctl, Duration::from_secs(1)).await {
                return Err(explain(body.first().copied().unwrap_or(ACK_FAILED)));
            }
            return Err(e);
        }

        let (kind, body) = self.channel.recv(ctl, HUMAN_TIMEOUT).await?;
        match kind {
            K_READY => {}
            K_REJECT => return Err(explain(body.first().copied().unwrap_or(ACK_FAILED))),
            _ => bail!("unexpected reply from the receiver"),
        }
        if be_u64(&body).unwrap_or(0) != 0 {
            bail!("resuming is not supported yet");
        }

        let mut buf = vec![0u8; PLAIN];
        let mut sent = 0u64;
        loop {
            ctl.checkpoint().await?;
            let n = reader.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            self.channel.send(K_DATA, &buf[..n]).await?;
            sent += n as u64;
            ctl.advance(n as u64);
        }
        if sent != size {
            bail!("the file changed while it was being sent");
        }
        self.channel.send(K_FIN, &sent.to_be_bytes()).await?;

        let (kind, body) = self.channel.recv(ctl, ACK_TIMEOUT).await?;
        match (kind, body.first().copied()) {
            (K_ACK, Some(ACK_OK)) => Ok(size),
            (K_ACK | K_REJECT, Some(code)) => Err(explain(code)),
            _ => bail!("unexpected reply from the receiver"),
        }
    }
}

pub struct RecvSession {
    channel: Channel,
    size: u64,
    pub peer: Peer,
}

pub async fn accept(listener: &TcpListener, identity: &Identity, ctl: &Control) -> Result<RecvSession> {
    let stream = loop {
        ctl.check()?;
        if let Ok(accepted) = timeout(POLL, listener.accept()).await {
            break accepted?.0;
        }
    };
    stream.set_nodelay(true)?;
    let (channel, peer) = handshake(stream, identity, false, ctl).await?;
    Ok(RecvSession { channel, size: 0, peer })
}

impl RecvSession {
    pub async fn hello(&mut self, ctl: &Control) -> Result<(String, u64)> {
        let (kind, body) = self.channel.recv(ctl, HUMAN_TIMEOUT).await?;
        if kind != K_HELLO || body.len() < 9 {
            bail!("bad introduction from the sender");
        }
        let size = be_u64(&body).context("bad introduction from the sender")?;
        let raw = String::from_utf8(body[8..].to_vec())?;
        let name = Path::new(&raw)
            .file_name()
            .context("bad file name")?
            .to_string_lossy()
            .into_owned();
        if name.len() > MAX_NAME {
            bail!("file name too long");
        }
        ctl.begin(&name, size);
        self.size = size;
        Ok((name, size))
    }

    pub async fn save_to<W: AsyncWrite + Unpin>(mut self, writer: &mut W, ctl: &Control) -> Result<()> {
        self.channel.send(K_READY, &0u64.to_be_bytes()).await?;
        let outcome = self.receive_body(writer, ctl).await;
        let code = match &outcome {
            Ok(()) => ACK_OK,
            Err(e) if e.downcast_ref::<snow::Error>().is_some() => ACK_CORRUPT,
            Err(_) => ACK_FAILED,
        };
        let _ = self.channel.send(K_ACK, &[code]).await;
        outcome
    }

    async fn receive_body<W: AsyncWrite + Unpin>(&mut self, writer: &mut W, ctl: &Control) -> Result<()> {
        let mut received = 0u64;
        loop {
            let (kind, body) = self.channel.recv(ctl, IDLE_TIMEOUT).await?;
            match kind {
                K_DATA => {
                    received += body.len() as u64;
                    if received > self.size {
                        bail!("the sender sent more data than announced");
                    }
                    writer.write_all(&body).await?;
                    ctl.advance(body.len() as u64);
                }
                K_FIN => {
                    if be_u64(&body) != Some(received) || received != self.size {
                        bail!("the transfer ended early");
                    }
                    writer.flush().await?;
                    return Ok(());
                }
                _ => bail!("unexpected message from the sender"),
            }
        }
    }

    pub async fn reject(mut self, code: u8) {
        let _ = self.channel.send(K_REJECT, &[code]).await;
    }
}

pub async fn send_file<A: ToSocketAddrs>(addr: A, path: &Path) -> Result<u64> {
    send_file_with(addr, path, &Identity::generate("flux-cli")?, &Control::new()).await
}

pub async fn send_file_with<A: ToSocketAddrs>(
    addr: A,
    path: &Path,
    identity: &Identity,
    ctl: &Control,
) -> Result<u64> {
    let name = path
        .file_name()
        .context("path has no file name")?
        .to_string_lossy()
        .into_owned();
    let file = File::open(path).await?;
    let size = file.metadata().await?.len();
    let session = connect(addr, identity, ctl).await?;
    session.send_stream(&name, size, file, ctl).await
}

pub async fn receive_one(listener: &TcpListener, out_dir: &Path) -> Result<PathBuf> {
    receive_one_with(listener, out_dir, &Identity::generate("flux-cli")?, &Control::new()).await
}

pub async fn receive_one_with(
    listener: &TcpListener,
    out_dir: &Path,
    identity: &Identity,
    ctl: &Control,
) -> Result<PathBuf> {
    let mut session = accept(listener, identity, ctl).await?;
    let (name, _size) = session.hello(ctl).await?;
    let dest = out_dir.join(&name);
    let mut file = File::create(&dest).await?;
    let outcome = session.save_to(&mut file, ctl).await;
    drop(file);
    if outcome.is_err() {
        tokio::fs::remove_file(&dest).await.ok();
    }
    outcome?;
    Ok(dest)
}
