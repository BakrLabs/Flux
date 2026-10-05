use std::net::{IpAddr, UdpSocket};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, ToSocketAddrs};

const CHUNK: usize = 256 * 1024;
const MAX_NAME: usize = 1024;

pub fn local_ip() -> Option<IpAddr> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    Some(sock.local_addr().ok()?.ip())
}

pub async fn send_file<A: ToSocketAddrs>(addr: A, path: &Path) -> Result<u64> {
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

    let mut stream = TcpStream::connect(addr).await?;
    stream.set_nodelay(true)?;
    stream.write_u16(name.len() as u16).await?;
    stream.write_all(name.as_bytes()).await?;
    stream.write_u64(size).await?;

    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        stream.write_all(&buf[..n]).await?;
    }
    stream.write_all(hasher.finalize().as_bytes()).await?;
    stream.flush().await?;
    Ok(size)
}

pub async fn receive_one(listener: &TcpListener, out_dir: &Path) -> Result<PathBuf> {
    let (mut stream, _) = listener.accept().await?;

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

    let dest = out_dir.join(name);
    let mut file = File::create(&dest).await?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    let mut remaining = size;

    while remaining > 0 {
        let want = remaining.min(CHUNK as u64) as usize;
        let n = stream.read(&mut buf[..want]).await?;
        if n == 0 {
            bail!("connection closed early");
        }
        hasher.update(&buf[..n]);
        file.write_all(&buf[..n]).await?;
        remaining -= n as u64;
    }

    let mut expected = [0u8; 32];
    stream.read_exact(&mut expected).await?;
    file.flush().await?;

    if hasher.finalize() != expected {
        tokio::fs::remove_file(&dest).await.ok();
        bail!("checksum mismatch");
    }
    Ok(dest)
}
