use sms_code_bridge::clipboard::{CodeSink, ConsoleNotifier, SystemClipboard};
use sms_code_bridge::config::Config;
use sms_code_bridge::history::HistoryStore;
use sms_code_bridge::pairing::{now_ms, PairingManager, DEFAULT_TTL_MS};
use sms_code_bridge::protocol::{Message, PROTOCOL_VERSION};
use sms_code_bridge::receiver::Receiver;
use sms_code_bridge::transport::{UdpTransport, MAX_FRAME_BYTES};

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

    let mut pairing = PairingManager::new(DEFAULT_TTL_MS);
    let (pairing_code, _salt) = pairing.issue(now_ms());

    let mut session: Option<Receiver> = None;
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
    println!("监听端口: {port}");
    println!("配对码: {pairing_code}（2 分钟内有效，配对成功即失效）");

    loop {
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
                    session = Some(Receiver::new(established.secret));
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
            Message::Code { .. } => {
                if let Some(receiver) = session.as_mut() {
                    if let Ok(code) = receiver.handle(&buffer[..size], now_ms()) {
                        sink.handle(&code);
                        history.append(&code, now_ms());
                        if let Some(path) = history_path.as_ref() {
                            let _ = history.save_to(path);
                        }
                    }
                }
            }
            Message::PairResponse { .. } => {}
        }
    }
}
