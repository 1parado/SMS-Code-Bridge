#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
// release 版 Windows 无控制台窗口，用户触达全部走系统弹窗与托盘（见 user_notice）

use sms_code_bridge::clipboard::{CodeSink, ConsoleNotifier, SystemClipboard};
use sms_code_bridge::config::Config;
use sms_code_bridge::connection::{Heartbeat, SessionRegistry, HEARTBEAT_TIMEOUT_MS};
use sms_code_bridge::devices::{DeviceStore, PairedDevice};
use sms_code_bridge::discovery::DISCOVERY_PORT;
use sms_code_bridge::history::HistoryStore;
#[cfg(all(windows, not(debug_assertions)))]
use sms_code_bridge::notifications;
use sms_code_bridge::pairing::{
    generate_secret, hex_decode, now_ms, session_id, to_hex, PairingManager, DEFAULT_TTL_MS,
};
use sms_code_bridge::protocol::{Message, PROTOCOL_VERSION};
use sms_code_bridge::transport::{UdpTransport, MAX_FRAME_BYTES};
#[cfg(windows)]
use sms_code_bridge::tray::{self, TrayAction};

/// 发现响应中的设备标识；不使用真实主机名，避免暴露本机信息。
const DEVICE_ID: &str = "pc-0001";
const DEVICE_NAME: &str = "SMS Code Bridge";
/// 一键配对请求的同意窗口：超过后需在手机端重新发起。
const PAIR_OPEN_WINDOW_MS: i64 = 60_000;
/// 「最近验证码」弹窗最多展示的条数。
const RECENT_CODES_SHOWN: usize = 10;

fn main() {
    if let Err(error) = run() {
        user_notice(&format!("运行失败: {error}"));
        std::process::exit(1);
    }
}

/// 面向用户的通知：release（无控制台）走系统弹窗，开发构建打印到控制台。
#[cfg(all(windows, not(debug_assertions)))]
fn user_notice(text: &str) {
    notifications::show_message("SMS Code Bridge", text);
}

#[cfg(any(not(windows), debug_assertions))]
fn user_notice(text: &str) {
    println!("{text}");
}

/// 开发日志：仅在保留控制台的构建中输出，release 下静默。
#[cfg(any(not(windows), debug_assertions))]
fn log(text: &str) {
    println!("{text}");
}

#[cfg(all(windows, not(debug_assertions)))]
fn log(_text: &str) {}

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
    let mut pairing_code = pairing.issue(now_ms());

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

    let mut discovery_buffer = [0u8; MAX_FRAME_BYTES];

    // 一键配对：手机端发起、电脑端用户在托盘确认，60 秒内有效
    let mut pending_open: Option<(String, std::net::SocketAddr, i64)> = None;

    // 托盘常驻（Windows）；菜单动作在主循环里轮询处理
    #[cfg(windows)]
    let tray_events = tray::spawn();

    user_notice(&format!(
        "SMS Code Bridge 已启动\n\n监听端口: {port}（发现端口 {DISCOVERY_PORT}）\n配对码: {pairing_code}（2 分钟内有效）\n\n手机端与电脑端保持同一局域网即可自动发现；也可以在手机端点「一键配对」后回到这里，于托盘菜单点「同意配对」。"
    ));

    loop {
        // 托盘菜单动作
        #[cfg(windows)]
        if let Ok(action) = tray_events.events.try_recv() {
            match action {
                TrayAction::ShowPairingCode => {
                    // 挑战过期、已消费或超次时重新发放，无需重启程序
                    if !pairing.has_active_challenge(now_ms()) {
                        pairing_code = pairing.issue(now_ms());
                        log("原配对码已失效，已重新发放");
                    }
                    user_notice(&format!(
                        "配对码: {pairing_code}（2 分钟内有效，配对成功即失效）"
                    ));
                }
                TrayAction::ApprovePairing => match pending_open.take() {
                    Some((device_id, addr, deadline)) if now_ms() <= deadline => {
                        let secret = generate_secret();
                        registry.establish(device_id.clone(), secret);
                        heartbeat.mark(now_ms());
                        devices.add(PairedDevice {
                            id: device_id.clone(),
                            name: device_id.clone(),
                            secret_hex: to_hex(&secret),
                            paired_at_ms: now_ms(),
                        });
                        let _ = devices.save();
                        let grant = Message::PairGrant {
                            v: PROTOCOL_VERSION,
                            device_id: device_id.clone(),
                            secret_hex: to_hex(&secret),
                            session_id: session_id(&secret),
                        };
                        if let Ok(json) = grant.to_json() {
                            let _ = transport.send_to(json.as_bytes(), addr);
                        }
                        user_notice(&format!(
                            "已同意设备 {device_id} 的一键配对，凭据已发送（仅限当前可信网络）"
                        ));
                    }
                    _ => {
                        user_notice("暂无待同意的配对请求\n\n请先在手机端点「一键配对」，60 秒内回到电脑端托盘点「同意配对」。");
                    }
                },
                TrayAction::ShowRecentCodes => {
                    if history.is_empty() {
                        user_notice("还没有收到过验证码");
                    } else {
                        let recent: Vec<String> = history
                            .entries()
                            .iter()
                            .rev()
                            .take(RECENT_CODES_SHOWN)
                            .map(|entry| format!("{}  {}", format_ts(entry.ts), entry.code))
                            .collect();
                        user_notice(&format!(
                            "最近验证码（新→旧，最多 {RECENT_CODES_SHOWN} 条）:\n\n{}",
                            recent.join("\n")
                        ));
                    }
                }
                TrayAction::Quit => {
                    log("收到退出请求，正在清理…");
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
                device_id,
                salt_hex,
                proof_hex,
                ..
            } => {
                // 盐值非法或证明校验不过（错码/过期/超次）都直接拒绝
                let outcome = hex_decode(&salt_hex)
                    .and_then(|salt| pairing.verify(&proof_hex, &salt, now_ms()));
                match outcome {
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
                        user_notice(&format!("设备 {device_id} 已配对：{from}"));
                    }
                    None => log(&format!("拒绝来自 {from} 的配对请求")),
                }
            }
            Message::PairOpenRequest { device_id, .. } => {
                pending_open = Some((device_id.clone(), from, now_ms() + PAIR_OPEN_WINDOW_MS));
                user_notice(&format!(
                    "设备 {device_id}（{from}）请求一键配对\n\n同意请点托盘菜单「同意配对」（{PAIR_OPEN_WINDOW_MS} 秒内有效）。\n仅建议在可信网络使用；公共网络请改用配对码配对。"
                ));
            }
            // 一键配对的应答只由手机端消费，电脑端收到即忽略
            Message::PairGrant { .. } => {}
            Message::Heartbeat { device_id, .. } => {
                // 仅当设备号匹配当前绑定设备时才打点，避免陌生设备续命
                if registry.is_bound() && registry.bound_device_id() == Some(device_id.as_str()) {
                    heartbeat.mark(now_ms());
                }
            }
            Message::Unpair {
                device_id,
                ts,
                mac_hex,
                ..
            } => {
                // 解绑必须携带会话密钥认证，防止局域网内伪造
                if registry.bound_device_id() == Some(device_id.as_str())
                    && registry.verify_unpair(ts, &mac_hex, now_ms())
                {
                    registry.unbind();
                    heartbeat = Heartbeat::new(HEARTBEAT_TIMEOUT_MS);
                    devices.clear();
                    let _ = devices.save();
                    pending_open = None;
                    user_notice(&format!("已与设备 {device_id} 解绑，密钥与配对已清除"));
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

    // 托盘「退出」走 break 到这里，正常结束
    Ok(())
}

/// 毫秒时间戳格式化为 `MM-dd HH:mm:ss`（本地时区），用于历史展示。
fn format_ts(ts: i64) -> String {
    // 距 epoch 的秒数；不引入 chrono，手写转换按 UTC 偏移近似展示
    let secs = ts.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let secs_of_day = secs.rem_euclid(86_400);
    let (hour, minute, second) = (
        secs_of_day / 3600,
        (secs_of_day % 3600) / 60,
        secs_of_day % 60,
    );
    // 1970-01-01 起的日期还原（简化算法，仅用于展示）
    let (year, month, day) = civil_from_days(days);
    format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}:{second:02}")
}

/// 天数转 (年, 月, 日)（Howard Hinnant 的 civil_from_days 算法）。
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}
