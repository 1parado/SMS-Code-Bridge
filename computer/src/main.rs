use sms_code_bridge::clipboard::{CodeSink, ConsoleNotifier, SystemClipboard};
use sms_code_bridge::config::Config;
use sms_code_bridge::connection::{Heartbeat, SessionRegistry, HEARTBEAT_TIMEOUT_MS};
use sms_code_bridge::devices::{DeviceStore, PairedDevice};
use sms_code_bridge::discovery::DISCOVERY_PORT;
use sms_code_bridge::history::HistoryStore;
use sms_code_bridge::pairing::{now_ms, PairingManager, DEFAULT_TTL_MS};
use sms_code_bridge::protocol::{Message, PROTOCOL_VERSION};
use sms_code_bridge::transport::{UdpTransport, MAX_FRAME_BYTES};
#[cfg(windows)]
use sms_code_bridge::tray::{self, TrayAction};

/// 发现响应中的设备标识；不使用真实主机名，避免暴露本机信息。
const DEVICE_ID: &str = "pc-0001";
const DEVICE_NAME: &str = "SMS Code Bridge";

fn main() {
    if let Err(error) = run() {
        eprintln!("运行失败: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let config = Config::load();
    let transport = UdpTransport::bind(config.port)?;
    let port = transport.local_addr()?.port();
    // 发现端口单独监听，手机端广播「谁在线」时在这里应答
    let discovery_socket = UdpTransport::bind(DISCOVERY_PORT)?;
    // 两个 socket 都设短超时，主循环交替轮询，避免互相阻塞
    transport.set_read_timeout_millis(200)?;
    discovery_socket.set_read_timeout_millis(200)?;

    let mut pairing = PairingManager::new(DEFAULT_TTL_MS);
    let (pairing_code, _salt) = pairing.issue(now_ms());

    let devices_path = DeviceStore::devices_path();
    let mut devices = DeviceStore::load(devices_path);
    let mut registry = SessionRegistry::new();
    // 若本机已持久化配对，则恢复会话（密钥仅在本机，绝不上网）
    if let Some(stored) = devices.list().first() {
        if let Some(secret) = hex_decode(&stored.secret_hex) {
            if secret.len() == 32 {
                let mut buf = [0u8; 32];
                buf.copy_from_slice(&secret);
                registry.establish(stored.id.clone(), buf);
            }
        }
    }
    let mut heartbeat = Heartbeat::new(HEARTBEAT_TIMEOUT_MS);
    if registry.is_bound() {
        heartbeat.mark(now_ms());
    }

    let mut sink = CodeSink::new(
        SystemClipboard,
        ConsoleNotifier,
        config.auto_copy,
        config.notify,
    );
    let history_path = HistoryStore::history_path();
    let mut history = HistoryStore::load_from(history_path.as_deref(), config.history_limit);
    let mut buffer = [0u8; MAX_FRAME_BYTES];

    println!("SMS Code Bridge 已启动");
    println!("监听端口: {port}（发现端口 {DISCOVERY_PORT}）");
    println!("配对码: {pairing_code}（2 分钟内有效，配对成功即失效）");

    let mut discovery_buffer = [0u8; MAX_FRAME_BYTES];

    // 托盘常驻（Windows）；菜单动作在主循环里轮询处理
    #[cfg(windows)]
    let tray_events = tray::spawn();

    loop {
        // 托盘菜单动作
        #[cfg(windows)]
        if let Ok(action) = tray_events.events.try_recv() {
            match action {
                TrayAction::ShowPairingCode => {
                    println!("配对码: {pairing_code}（如已过期请重启程序刷新）");
                }
                TrayAction::Quit => {
                    println!("收到退出请求，正在清理…");
                    break;
                }
            }
        }

        // 处理局域网发现：只回应「谁在线」，不含任何敏感信息
        if let Ok((size, from)) = discovery_socket.recv(&mut discovery_buffer) {
            if let Ok(text) = std::str::from_utf8(&discovery_buffer[..size]) {
                if matches!(
                    Message::from_json(text),
                    Ok(Message::DiscoveryRequest { .. })
                ) {
                    let response = Message::DiscoveryResponse {
                        v: PROTOCOL_VERSION,
                        device_id: DEVICE_ID.to_string(),
                        name: DEVICE_NAME.to_string(),
                        port,
                    };
                    if let Ok(json) = response.to_json() {
                        let _ = discovery_socket.send_to(json.as_bytes(), from);
                    }
                }
            }
        }

        let (size, from) = match transport.recv(&mut buffer) {
            Ok(value) => value,
            Err(_) => continue,
        };
        let Ok(text) = std::str::from_utf8(&buffer[..size]) else {
            continue;
        };
        let Ok(message) = Message::from_json(text) else {
            continue;
        };

        match message {
            Message::PairRequest {
                code, device_id, ..
            } => match pairing.verify(&code, now_ms()) {
                Some(established) => {
                    registry.establish(device_id.clone(), established.secret);
                    heartbeat.mark(now_ms());
                    // 持久化已配对设备（密钥仅存本机，绝不上网传输）
                    devices.add(PairedDevice {
                        id: device_id.clone(),
                        name: device_id.clone(),
                        secret_hex: to_hex(&established.secret),
                        paired_at_ms: now_ms(),
                    });
                    let _ = devices.save();
                    let response = Message::PairResponse {
                        v: PROTOCOL_VERSION,
                        ok: true,
                        session_id: Some(established.session_id),
                    };
                    if let Ok(json) = response.to_json() {
                        let _ = transport.send_to(json.as_bytes(), from);
                    }
                    println!("设备 {device_id} 已配对：{from}");
                }
                None => println!("拒绝来自 {from} 的配对请求"),
            },
            Message::Heartbeat { device_id, .. } => {
                // 仅当设备号匹配当前绑定设备时才打点，避免陌生设备续命
                if registry.is_bound() && registry.bound_device_id() == Some(device_id.as_str()) {
                    heartbeat.mark(now_ms());
                }
            }
            Message::Unpair { device_id, .. } => {
                if registry.bound_device_id() == Some(device_id.as_str()) {
                    registry.unbind();
                    heartbeat = Heartbeat::new(HEARTBEAT_TIMEOUT_MS);
                    devices.clear();
                    let _ = devices.save();
                    println!("已与设备 {device_id} 解绑，密钥与配对已清除");
                }
            }
            Message::Code { .. } => {
                if let Some(code) = registry.handle(&buffer[..size], now_ms()) {
                    // 来自已绑定设备的任何消息都证明连接存活
                    heartbeat.mark(now_ms());
                    sink.handle(&code);
                    history.append(&code, now_ms());
                    if let Some(path) = history_path.as_ref() {
                        let _ = history.save_to(path);
                    }
                }
            }
            Message::PairResponse { .. } => {}
            Message::DiscoveryRequest { .. } => {
                // 数据端口上也允许发现，方便已配对设备刷新地址
                let response = Message::DiscoveryResponse {
                    v: PROTOCOL_VERSION,
                    device_id: DEVICE_ID.to_string(),
                    name: DEVICE_NAME.to_string(),
                    port,
                };
                if let Ok(json) = response.to_json() {
                    let _ = transport.send_to(json.as_bytes(), from);
                }
            }
            Message::DiscoveryResponse { .. } => {}
        }
    }
}

/// 字节切片转小写十六进制（用于持久化密钥）。
fn to_hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

/// 十六进制转字节；长度非偶数或含非法字符时返回 None。
fn hex_decode(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .ok()
}
