use std::env;
use std::path::{Path, PathBuf};
use std::process;
use std::time::Instant;

use flux_core::{local_ip, receive_one, send_file};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        eprintln!("error: {e:#}");
        process::exit(1);
    }
}

async fn run() -> anyhow::Result<()> {
    let args: Vec<String> = env::args().skip(1).collect();
    match args.as_slice() {
        [cmd, rest @ ..] if cmd == "receive" => {
            let dir = rest.first().map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
            let listener = TcpListener::bind("0.0.0.0:0").await?;
            let port = listener.local_addr()?.port();
            match local_ip() {
                Some(ip) => println!("listening on {ip}:{port}"),
                None => println!("listening on port {port}"),
            }
            let saved = receive_one(&listener, &dir).await?;
            println!("saved {}", saved.display());
        }
        [cmd, addr, file] if cmd == "send" => {
            let started = Instant::now();
            let size = send_file(addr.as_str(), Path::new(file)).await?;
            let secs = started.elapsed().as_secs_f64();
            println!("sent {size} bytes in {secs:.2}s ({:.1} MB/s)", size as f64 / secs / 1e6);
        }
        _ => eprintln!("usage:\n  flux receive [dir]\n  flux send <ip:port> <file>"),
    }
    Ok(())
}
