//! jasktv-core's tray icon (Windows). Core runs as a background task with nothing on screen; this small program
//! starts when you log in and shows how it's doing:
//!  - the icon: light when all is well, amber when something needs a look (not signed in, a service stopped, a list
//!    that failed to fetch), red when core doesn't answer;
//!  - its tooltip and the top of its menu: the details;
//!  - the menu: core's status page, the Control Panel, the log and folder, "Refresh lists now", restarting Prowlarr
//!    or TorrServer, starting at login, and closing the tray (core keeps running).
//! It lives beside jasktv-core.exe and reads core's config and access secret from there.
#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(not(windows))]
fn main() { eprintln!("jasktv-tray is for Windows; on Linux, see `systemctl status jasktv-core`."); }

#[cfg(windows)]
fn main() { win::run(); }

#[cfg(windows)]
mod win {
    use serde_json::Value;
    use std::os::windows::process::CommandExt;
    use std::path::PathBuf;
    use std::process::Command;
    use std::time::{Duration, Instant};
    use tray_icon::menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem};
    use tray_icon::{Icon, TrayIconBuilder};
    use windows_sys::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{DispatchMessageW, PeekMessageW, TranslateMessage, MSG, PM_REMOVE};

    const NO_WINDOW: u32 = 0x0800_0000;
    const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";

    #[derive(Clone, Copy, PartialEq)]
    enum Health { Good, Attention, Down }

    struct Core { dir: PathBuf, port: u16, panel: String }

    impl Core {
        fn find() -> Core {
            let dir = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf())).unwrap_or_else(|| ".".into());
            let cfg: Value = std::fs::read_to_string(dir.join("jasktv-core.json")).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Value::Null);
            let port = cfg["listen"].as_str().and_then(|l| l.rsplit(':').next()).and_then(|p| p.parse().ok()).unwrap_or(7000);
            let panel = cfg["account_server"].as_str().map(|s| format!("{}/panel/", s.trim_end_matches('/'))).unwrap_or_default();
            Core { dir, port, panel }
        }
        /// Core's access secret, from its state file (it can change: read it each time).
        fn secret(&self) -> String {
            std::fs::read_to_string(self.dir.join("jasktv-core-state.json")).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok())
                .and_then(|v| v["access_secret"].as_str().map(str::to_string)).unwrap_or_default()
        }
        fn agent(&self) -> ureq::Agent { ureq::AgentBuilder::new().timeout(Duration::from_secs(4)).build() }
        fn health(&self) -> Option<Value> {
            self.agent().get(&format!("http://127.0.0.1:{}/health", self.port)).set("Authorization", &format!("Bearer {}", self.secret()))
                .call().ok()?.into_json().ok()
        }
        fn action(&self, path: &str) -> String {
            match self.agent().post(&format!("http://127.0.0.1:{}/action/{path}", self.port)).set("Authorization", &format!("Bearer {}", self.secret())).call() {
                Ok(r) => r.into_json::<Value>().ok().and_then(|v| v["message"].as_str().map(str::to_string)).unwrap_or_else(|| "done".into()),
                Err(_) => "core didn't answer".into(),
            }
        }
    }

    fn open(target: &str) { let _ = Command::new("cmd").args(["/C", "start", "", target]).creation_flags(NO_WINDOW).spawn(); }

    fn reg(args: &[&str]) -> bool { Command::new("reg").args(args).creation_flags(NO_WINDOW).output().map(|o| o.status.success()).unwrap_or(false) }
    fn autostart() -> bool { reg(&["query", RUN_KEY, "/v", "jasktv-tray"]) }
    fn set_autostart(on: bool) {
        let exe = std::env::current_exe().map(|p| format!("\"{}\"", p.display())).unwrap_or_default();
        if on { reg(&["add", RUN_KEY, "/v", "jasktv-tray", "/t", "REG_SZ", "/d", &exe, "/f"]); } else { reg(&["delete", RUN_KEY, "/v", "jasktv-tray", "/f"]); }
    }

    fn ago(ms: i64) -> String {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
        let s = (now - ms).max(0) / 1000;
        if ms <= 0 { "never".into() } else if s < 90 { "just now".into() } else if s < 5400 { format!("{} min ago", s / 60) }
        else if s < 172_800 { format!("{} h ago", s / 3600) } else { format!("{} d ago", s / 86400) }
    }
    fn num(n: i64) -> String {
        let s = n.to_string();
        s.as_bytes().rchunks(3).rev().map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join(",")
    }

    /// (health, headline, lines for the menu) from core's /health.
    fn read(h: Option<&Value>) -> (Health, String, Vec<String>) {
        let Some(h) = h else { return (Health::Down, "jasktv-core isn't running".into(), vec!["It starts with Windows (Task Scheduler: jasktv-core).".into()]) };
        let mut attention = Vec::new();
        if h["account"].as_str() != Some("signed_in") { attention.push("not signed in to your jaskTV account".to_string()); }
        let mut lines = Vec::new();
        let m = &h["matching"];
        if m["films_checked"].as_i64().unwrap_or(0) > 0 {
            lines.push(format!("Matching: {} of {} films", num(m["films_matched"].as_i64().unwrap_or(0)), num(m["films_checked"].as_i64().unwrap_or(0))));
        }
        if h["shared"]["building"].as_bool() == Some(true) { lines.push("Builds your account's match table".into()); }
        else if let Some(b) = h["shared"]["builder"]["name"].as_str() { lines.push(format!("Copies the match table from {b}")); }
        let newest = h["provider"].as_object().map(|p| p.values().filter_map(|v| v["fetched_at"].as_i64()).max().unwrap_or(0)).unwrap_or(0);
        if newest > 0 { lines.push(format!("Lists fetched {}", ago(newest))); }
        if let Some(p) = h["provider"].as_object() {
            if p.values().any(|v| v["last_error"].is_string()) { attention.push("a list failed to fetch".into()); }
        }
        let trackers = h["indexers"].as_array().map(|a| a.len()).unwrap_or(0);
        for (key, label) in [("prowlarr", "Prowlarr"), ("torrserver", "TorrServer")] {
            let s = &h["services"][key];
            if s.is_null() { continue; }
            let running = s["running"].as_bool() == Some(true);
            if !running { attention.push(format!("{label} stopped")); }
            lines.push(match (key, running) {
                ("prowlarr", true) => format!("Prowlarr: running · {trackers} trackers"),
                (_, true) => format!("{label}: running"),
                _ => format!("{label}: stopped"),
            });
        }
        if attention.is_empty() { (Health::Good, "jasktv-core is running".into(), lines) }
        else { (Health::Attention, format!("jasktv-core: {}", attention.join(", ")), lines) }
    }

    /// A 32×32 TV outline in the health's colour.
    fn icon(health: Health) -> Icon {
        let (r, g, b) = match health { Health::Good => (0xEC, 0xED, 0xEF), Health::Attention => (0xE8, 0xC3, 0x7A), Health::Down => (0xEE, 0x8B, 0x84) };
        let mut px = vec![0u8; 32 * 32 * 4];
        let mut put = |x: i32, y: i32, a: u8| { if (0..32).contains(&x) && (0..32).contains(&y) { let i = ((y * 32 + x) * 4) as usize; px[i..i + 4].copy_from_slice(&[r, g, b, a]); } };
        // Screen: a rounded rectangle 3 px thick, then the stand.
        for y in 4..24 { for x in 2..30 {
            let edge = x < 5 || x > 26 || y < 7 || y > 20;
            let corner = (x < 4 || x > 27) && (y < 6 || y > 21);
            if edge && !corner { put(x, y, 255); }
        } }
        for x in 10..22 { for y in 26..29 { put(x, y, 255); } }
        for x in 15..17 { for y in 24..26 { put(x, y, 255); } }
        if health != Health::Good { for x in 13..19 { for y in 11..17 { put(x, y, 255); } } }
        Icon::from_rgba(px, 32, 32).expect("icon")
    }

    pub fn run() {
        // One tray at a time.
        let name: Vec<u16> = "Local\\jasktv-tray\0".encode_utf16().collect();
        unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()); if GetLastError() == ERROR_ALREADY_EXISTS { return; } }
        let core = Core::find();
        // First run: start with Windows from now on (the menu turns it off, and that sticks).
        let marker = core.dir.join("tray-autostart-set");
        if !marker.exists() { set_autostart(true); let _ = std::fs::write(&marker, "1"); }

        let menu = Menu::new();
        let headline = MenuItem::new("Checking…", false, None);
        let details: Vec<MenuItem> = (0..5).map(|_| MenuItem::new("", false, None)).collect();
        let page = MenuItem::new("Open status page", true, None);
        let panel = MenuItem::new("Open Control Panel", !core.panel.is_empty(), None);
        let refresh = MenuItem::new("Refresh lists now", true, None);
        let r_prowlarr = MenuItem::new("Restart Prowlarr", true, None);
        let r_torr = MenuItem::new("Restart TorrServer", true, None);
        let log = MenuItem::new("Open log", true, None);
        let folder = MenuItem::new("Open folder", true, None);
        let login = CheckMenuItem::new("Start at login", true, autostart(), None);
        let close = MenuItem::new("Close tray (core keeps running)", true, None);
        let _ = menu.append(&headline);
        for d in &details { let _ = menu.append(d); }
        let _ = menu.append_items(&[&PredefinedMenuItem::separator(), &page, &panel, &PredefinedMenuItem::separator(), &refresh, &r_prowlarr, &r_torr,
            &PredefinedMenuItem::separator(), &log, &folder, &login, &close]);
        let tray = TrayIconBuilder::new().with_menu(Box::new(menu)).with_tooltip("jasktv-core").with_icon(icon(Health::Down)).build().expect("tray icon");

        let mut shown: Option<Health> = None;
        let mut checked = Instant::now() - Duration::from_secs(60);
        let mut note: Option<(String, Instant)> = None;
        loop {
            unsafe {
                let mut msg: MSG = std::mem::zeroed();
                while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 { TranslateMessage(&msg); DispatchMessageW(&msg); }
            }
            if let Ok(ev) = MenuEvent::receiver().try_recv() {
                let id = ev.id;
                let mut said = None;
                if id == page.id() { open(&format!("http://localhost:{}/", core.port)); }
                else if id == panel.id() { open(&core.panel); }
                else if id == refresh.id() { said = Some(core.action("refresh-lists")); }
                else if id == r_prowlarr.id() { said = Some(core.action("restart/prowlarr")); }
                else if id == r_torr.id() { said = Some(core.action("restart/torrserver")); }
                else if id == log.id() { open(&core.dir.join("core.log").display().to_string()); }
                else if id == folder.id() { open(&core.dir.display().to_string()); }
                else if id == login.id() { set_autostart(login.is_checked()); }
                else if id == close.id() { return; }
                if let Some(s) = said { note = Some((s, Instant::now())); checked = Instant::now() - Duration::from_secs(60); }
            }
            if checked.elapsed() >= Duration::from_secs(15) {
                checked = Instant::now();
                let (health, head, lines) = read(core.health().as_ref());
                let head = match &note { Some((n, at)) if at.elapsed() < Duration::from_secs(60) => format!("{head} · {n}"), _ => head };
                headline.set_text(&head);
                for (i, d) in details.iter().enumerate() { d.set_text(lines.get(i).map(String::as_str).unwrap_or("")); }
                let tip: String = std::iter::once(head.clone()).chain(lines.iter().take(2).cloned()).collect::<Vec<_>>().join("\n");
                let _ = tray.set_tooltip(Some(tip.chars().take(120).collect::<String>()));
                if shown != Some(health) { let _ = tray.set_icon(Some(icon(health))); shown = Some(health); }
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}
