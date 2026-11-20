//! jaskTV devices on the home network: each announces `_jasktv._tcp` with its device id in TXT "d" (the app's
//! `LanDiscovery`). Core uses them to copy TMDB matches devices already made, before matching anything itself.

use std::net::SocketAddr;
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct Peer { pub device: String, pub addr: SocketAddr }

/// The devices that answer within [wait].
pub fn browse(wait: Duration) -> Result<Vec<Peer>, String> {
    let daemon = mdns_sd::ServiceDaemon::new().map_err(|e| e.to_string())?;
    let rx = daemon.browse("_jasktv._tcp.local.").map_err(|e| e.to_string())?;
    let until = Instant::now() + wait;
    let mut out: Vec<Peer> = Vec::new();
    while let Some(left) = until.checked_duration_since(Instant::now()) {
        match rx.recv_timeout(left) {
            Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) => {
                let Some(device) = info.get_property_val_str("d").map(str::to_string) else { continue };
                // IPv4 first: phones and TVs listen there.
                let mut addrs: Vec<_> = info.get_addresses().iter().copied().collect();
                addrs.sort_by_key(|a| !a.is_ipv4());
                if let Some(ip) = addrs.first() {
                    if !out.iter().any(|p| p.device == device) { out.push(Peer { device, addr: SocketAddr::new(*ip, info.get_port()) }); }
                }
            }
            Ok(_) => {}
            Err(_) => break,
        }
    }
    let _ = daemon.shutdown();
    Ok(out)
}
