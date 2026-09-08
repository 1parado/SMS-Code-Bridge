//! 连接健康管理：心跳存活检测、指数退避重连调度、会话注册表。
//!
//! 纯逻辑，便于单元测试；不依赖任何平台能力。

use crate::receiver::Receiver;
use std::time::Duration;

/// 心跳超时阈值（毫秒）：超过该时长未收到对端心跳即判定断连。
pub const HEARTBEAT_TIMEOUT_MS: u64 = 15_000;
/// 重连退避基线（毫秒）。
pub const RECONNECT_BASE_MS: u64 = 1_000;
/// 重连退避系数。
pub const RECONNECT_FACTOR: u32 = 2;
/// 重连退避上限（毫秒）。
pub const RECONNECT_MAX_MS: u64 = 30_000;

/// 心跳存活跟踪器：记录最近一次收到对端消息的时间。
#[derive(Debug, Clone)]
pub struct Heartbeat {
    timeout_ms: u64,
    last_seen_ms: i64,
}

impl Heartbeat {
    pub fn new(timeout_ms: u64) -> Self {
        Self {
            timeout_ms,
            last_seen_ms: -1,
        }
    }

    /// 收到对端心跳时打点。
    pub fn mark(&mut self, now_ms: i64) {
        self.last_seen_ms = now_ms;
    }

    /// 是否仍存活：从未收到过心跳视为未连接（false）；
    /// 最近一次在超时窗口内则存活（true）。
    pub fn is_alive(&self, now_ms: i64) -> bool {
        self.last_seen_ms >= 0 && (now_ms - self.last_seen_ms) <= self.timeout_ms as i64
    }
}

/// 指数退避重连调度器：delay = base * factor^attempt，封顶 max。
#[derive(Debug, Clone)]
pub struct Backoff {
    base_ms: u64,
    factor: u32,
    max_ms: u64,
    attempt: u32,
}

impl Backoff {
    pub fn new(base_ms: u64, factor: u32, max_ms: u64) -> Self {
        Self {
            base_ms,
            factor,
            max_ms,
            attempt: 0,
        }
    }

    /// 返回下一次重连延迟并推进计数。
    pub fn next_delay(&mut self) -> Duration {
        let mut delay = self.base_ms;
        for _ in 0..self.attempt {
            delay = if delay >= self.max_ms {
                self.max_ms
            } else {
                (delay.saturating_mul(self.factor as u64)).min(self.max_ms)
            };
        }
        self.attempt += 1;
        Duration::from_millis(delay)
    }

    /// 连接恢复后重置退避计数。
    pub fn reset(&mut self) {
        self.attempt = 0;
    }
}

/// 会话注册表：维护当前已配对设备的会话与密钥。
///
/// 解绑后彻底清空，使后续任何来自该设备的消息都被拒绝。
#[derive(Debug, Default)]
pub struct SessionRegistry {
    device_id: Option<String>,
    receiver: Option<Receiver>,
}

impl SessionRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// 配对成功：建立会话并保存设备号与派生密钥。
    pub fn establish(&mut self, device_id: String, secret: [u8; 32]) {
        self.device_id = Some(device_id);
        self.receiver = Some(Receiver::new(secret));
    }

    /// 一键解绑：清空密钥与配对（设备号一并清除）。
    pub fn unbind(&mut self) {
        self.device_id = None;
        self.receiver = None;
    }

    pub fn is_bound(&self) -> bool {
        self.receiver.is_some()
    }

    pub fn bound_device_id(&self) -> Option<&str> {
        self.device_id.as_deref()
    }

    /// 处理验证码消息；未绑定或 MAC 校验失败返回 None（即拒绝）。
    pub fn handle(&mut self, buf: &[u8], now: i64) -> Option<String> {
        let receiver = self.receiver.as_mut()?;
        receiver.handle(buf, now).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::devices::{DeviceStore, PairedDevice};
    use crate::protocol::{Message, PROTOCOL_VERSION};
    use std::fs;
    use std::path::PathBuf;

    #[test]
    fn heartbeat_timeout_marks_disconnected() {
        let mut hb = Heartbeat::new(15_000);
        hb.mark(1_000);
        // 恰好在超时边界内 → 存活
        assert!(hb.is_alive(1_000 + 15_000));
        // 越过边界 → 断连
        assert!(!hb.is_alive(1_000 + 15_001));
        // 从未收到心跳 → 未连接
        assert!(!Heartbeat::new(15_000).is_alive(999_999));
    }

    #[test]
    fn reconnect_backoff_sequence() {
        let mut backoff = Backoff::new(RECONNECT_BASE_MS, RECONNECT_FACTOR, RECONNECT_MAX_MS);
        assert_eq!(backoff.next_delay(), Duration::from_millis(1_000));
        assert_eq!(backoff.next_delay(), Duration::from_millis(2_000));
        assert_eq!(backoff.next_delay(), Duration::from_millis(4_000));
        assert_eq!(backoff.next_delay(), Duration::from_millis(8_000));
        assert_eq!(backoff.next_delay(), Duration::from_millis(16_000));
        // 封顶
        assert_eq!(backoff.next_delay(), Duration::from_millis(30_000));
        assert_eq!(backoff.next_delay(), Duration::from_millis(30_000));
        // 连接恢复后重置
        backoff.reset();
        assert_eq!(backoff.next_delay(), Duration::from_millis(1_000));
    }

    #[test]
    fn unbind_clears_credentials() {
        let mut reg = SessionRegistry::new();
        reg.establish("device-0001".to_string(), [7u8; 32]);
        assert!(reg.is_bound());
        assert_eq!(reg.bound_device_id(), Some("device-0001"));
        reg.unbind();
        assert!(!reg.is_bound());
        assert!(reg.bound_device_id().is_none());
    }

    #[test]
    fn message_from_unbound_device_rejected() {
        let mut reg = SessionRegistry::new();
        let message = Message::Code {
            v: PROTOCOL_VERSION,
            code: "482913".to_string(),
            ts: 1,
            nonce: "n-0001".to_string(),
            mac: "deadbeef".to_string(),
        };
        let json = message.to_json().expect("序列化失败");

        // 未绑定时，任何验证码消息都不应被处理
        assert!(reg.handle(json.as_bytes(), 1).is_none());

        // 绑定后解绑，仍应被拒绝
        reg.establish("device-0001".to_string(), [7u8; 32]);
        reg.unbind();
        assert!(reg.handle(json.as_bytes(), 1).is_none());
    }

    #[test]
    fn unbind_clears_credentials_and_persists() {
        let dir = std::env::temp_dir().join(format!(
            "sms-code-bridge-unbind-{}-{}",
            std::process::id(),
            "persist"
        ));
        fs::create_dir_all(&dir).expect("创建临时目录失败");
        let path: PathBuf = dir.join("devices.json");

        let mut store = DeviceStore::load(Some(path.clone()));
        store.add(PairedDevice {
            id: "device-0001".to_string(),
            name: "Pixel".to_string(),
            secret_hex: "aabbccdd".to_string(),
            paired_at_ms: 1_000,
        });
        store.save().expect("保存失败");

        store.clear();
        store.save().expect("保存失败");

        let reloaded = DeviceStore::load(Some(path));
        assert!(reloaded.list().is_empty(), "解绑后已配对凭据不应残留");
        fs::remove_dir_all(&dir).ok();
    }
}
