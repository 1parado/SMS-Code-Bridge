//! 接收侧校验：解包、MAC 校验、重放与去重。
//!
//! 时间以毫秒时间戳入参注入，便于测试。所有非法消息一律丢弃并记录原因。

use crate::pairing::to_hex;
use crate::protocol::Message;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::collections::{HashMap, VecDeque};

type HmacSha256 = Hmac<Sha256>;

/// 消息被拒绝的原因（用于日志与排障，不含任何敏感内容）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    Oversized,
    Malformed,
    UnsupportedVersion,
    UnexpectedType,
    BadMac,
    Expired,
    Replay,
    Duplicate,
}

/// 默认重放窗口：5 分钟。
pub const DEFAULT_REPLAY_WINDOW_MS: i64 = 300_000;
/// 默认去重窗口：10 秒内同一验证码不再写入剪贴板。
pub const DEFAULT_DEDUP_WINDOW_MS: i64 = 10_000;
/// 默认最大包体：4KB，远超实际消息体积。
pub const DEFAULT_MAX_PACKET_BYTES: usize = 4096;

pub struct Receiver {
    secret: [u8; 32],
    replay_window_ms: i64,
    dedup_window_ms: i64,
    max_packet_bytes: usize,
    seen_nonces: HashMap<String, i64>,
    recent: VecDeque<(i64, String)>,
}

impl std::fmt::Debug for Receiver {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // 注意：secret 属敏感凭据，Debug 输出中一律脱敏，仅暴露非敏感的运行期计数。
        f.debug_struct("Receiver")
            .field("replay_window_ms", &self.replay_window_ms)
            .field("dedup_window_ms", &self.dedup_window_ms)
            .field("max_packet_bytes", &self.max_packet_bytes)
            .field("seen_nonces_len", &self.seen_nonces.len())
            .field("recent_len", &self.recent.len())
            .field("secret", &"<redacted>")
            .finish()
    }
}

impl Receiver {
    pub fn new(secret: [u8; 32]) -> Self {
        Self {
            secret,
            replay_window_ms: DEFAULT_REPLAY_WINDOW_MS,
            dedup_window_ms: DEFAULT_DEDUP_WINDOW_MS,
            max_packet_bytes: DEFAULT_MAX_PACKET_BYTES,
            seen_nonces: HashMap::new(),
            recent: VecDeque::new(),
        }
    }

    pub fn with_windows(mut self, replay_window_ms: i64, dedup_window_ms: i64) -> Self {
        self.replay_window_ms = replay_window_ms;
        self.dedup_window_ms = dedup_window_ms;
        self
    }

    /// 计算消息签名：HMAC-SHA256(secret, "code|ts|nonce")。
    pub fn sign(&self, code: &str, ts: i64, nonce: &str) -> String {
        to_hex(&Self::mac(&self.secret, &Self::payload(code, ts, nonce)))
    }

    fn payload(code: &str, ts: i64, nonce: &str) -> String {
        format!("{code}|{ts}|{nonce}")
    }

    fn mac(secret: &[u8], payload: &str) -> [u8; 32] {
        let mut mac = HmacSha256::new_from_slice(secret).expect("HMAC 接受任意长度密钥");
        mac.update(payload.as_bytes());
        let bytes = mac.finalize().into_bytes();
        let mut out = [0u8; 32];
        out.copy_from_slice(&bytes);
        out
    }

    /// 处理一帧数据；通过全部校验时返回验证码。
    pub fn handle(&mut self, raw: &[u8], now_ms: i64) -> Result<String, RejectReason> {
        if raw.len() > self.max_packet_bytes {
            return Err(RejectReason::Oversized);
        }
        let text = std::str::from_utf8(raw).map_err(|_| RejectReason::Malformed)?;
        let message = Message::from_json(text).map_err(|_| RejectReason::Malformed)?;
        if !message.is_supported() {
            return Err(RejectReason::UnsupportedVersion);
        }

        let Message::Code {
            code,
            ts,
            nonce,
            mac,
            ..
        } = message
        else {
            return Err(RejectReason::UnexpectedType);
        };

        let expected = to_hex(&Self::mac(&self.secret, &Self::payload(&code, ts, &nonce)));
        if expected != mac {
            return Err(RejectReason::BadMac);
        }

        if (now_ms - ts).abs() > self.replay_window_ms {
            return Err(RejectReason::Expired);
        }
        if self.seen_nonces.contains_key(&nonce) {
            return Err(RejectReason::Replay);
        }
        if self.is_duplicate(&code, now_ms) {
            return Err(RejectReason::Duplicate);
        }

        self.forget_expired(now_ms);
        self.seen_nonces.insert(nonce.clone(), ts);
        self.recent.push_back((now_ms, code.clone()));
        Ok(code)
    }

    fn is_duplicate(&self, code: &str, now_ms: i64) -> bool {
        self.recent.iter().any(|(ts, recent)| {
            *recent == code && now_ms.saturating_sub(*ts) <= self.dedup_window_ms
        })
    }

    fn forget_expired(&mut self, now_ms: i64) {
        self.seen_nonces
            .retain(|_, ts| (now_ms - *ts).abs() <= self.replay_window_ms);
        while let Some((ts, _)) = self.recent.front() {
            if now_ms.saturating_sub(*ts) > self.dedup_window_ms {
                self.recent.pop_front();
            } else {
                break;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: [u8; 32] = [7u8; 32];

    fn receiver() -> Receiver {
        Receiver::new(SECRET).with_windows(300_000, 10_000)
    }

    fn frame(receiver: &Receiver, code: &str, ts: i64, nonce: &str, mac: Option<&str>) -> Vec<u8> {
        let mac = mac
            .map(str::to_string)
            .unwrap_or_else(|| receiver.sign(code, ts, nonce));
        Message::Code {
            v: crate::protocol::PROTOCOL_VERSION,
            code: code.to_string(),
            ts,
            nonce: nonce.to_string(),
            mac,
        }
        .to_json()
        .expect("序列化失败")
        .into_bytes()
    }

    #[test]
    fn valid_message_accepted() {
        let mut receiver = receiver();
        let raw = frame(&receiver, "482913", 1_000, "n-1", None);
        assert_eq!(receiver.handle(&raw, 1_100).expect("应通过"), "482913");
    }

    #[test]
    fn invalid_hmac_rejected() {
        let mut receiver = receiver();
        let raw = frame(&receiver, "482913", 1_000, "n-1", Some("deadbeef"));
        assert_eq!(receiver.handle(&raw, 1_100), Err(RejectReason::BadMac));
    }

    #[test]
    fn mac_from_other_secret_rejected() {
        let mut receiver = receiver();
        let other = Receiver::new([9u8; 32]);
        let raw = frame(&other, "482913", 1_000, "n-1", None);
        assert_eq!(receiver.handle(&raw, 1_100), Err(RejectReason::BadMac));
    }

    #[test]
    fn replay_outside_window_rejected() {
        let mut receiver = Receiver::new(SECRET).with_windows(1_000, 10_000);
        let raw = frame(&receiver, "482913", 1_000, "n-1", None);
        assert_eq!(receiver.handle(&raw, 5_000), Err(RejectReason::Expired));
    }

    #[test]
    fn same_nonce_rejected_as_replay() {
        let mut receiver = receiver();
        let first = frame(&receiver, "482913", 1_000, "n-1", None);
        let second = frame(&receiver, "482913", 1_100, "n-1", None);
        assert!(receiver.handle(&first, 1_050).is_ok());
        assert_eq!(receiver.handle(&second, 1_150), Err(RejectReason::Replay));
    }

    #[test]
    fn duplicate_code_within_window_dropped() {
        let mut receiver = Receiver::new(SECRET).with_windows(300_000, 10_000);
        let first = frame(&receiver, "482913", 1_000, "n-1", None);
        let second = frame(&receiver, "482913", 1_500, "n-2", None);
        assert!(receiver.handle(&first, 1_050).is_ok());
        assert_eq!(
            receiver.handle(&second, 1_550),
            Err(RejectReason::Duplicate)
        );
    }

    #[test]
    fn same_code_after_dedup_window_accepted() {
        let mut receiver = Receiver::new(SECRET).with_windows(300_000, 1_000);
        let first = frame(&receiver, "482913", 1_000, "n-1", None);
        let second = frame(&receiver, "482913", 5_000, "n-2", None);
        assert!(receiver.handle(&first, 1_050).is_ok());
        assert!(receiver.handle(&second, 5_050).is_ok());
    }

    #[test]
    fn oversized_packet_rejected() {
        let mut receiver = Receiver::new(SECRET);
        let raw = vec![b'a'; DEFAULT_MAX_PACKET_BYTES + 1];
        assert_eq!(receiver.handle(&raw, 0), Err(RejectReason::Oversized));
    }

    #[test]
    fn malformed_payload_rejected() {
        let mut receiver = receiver();
        assert_eq!(
            receiver.handle(b"{ not json", 0),
            Err(RejectReason::Malformed)
        );
    }

    #[test]
    fn wrong_message_type_rejected() {
        let mut receiver = receiver();
        let raw = Message::PairResponse {
            v: crate::protocol::PROTOCOL_VERSION,
            ok: true,
            session_id: None,
        }
        .to_json()
        .expect("序列化失败")
        .into_bytes();
        assert_eq!(receiver.handle(&raw, 0), Err(RejectReason::UnexpectedType));
    }

    #[test]
    fn sign_is_stable_and_secret_dependent() {
        let receiver = receiver();
        let other = Receiver::new([1u8; 32]);
        assert_eq!(
            receiver.sign("482913", 1, "n"),
            receiver.sign("482913", 1, "n")
        );
        assert_ne!(
            receiver.sign("482913", 1, "n"),
            other.sign("482913", 1, "n")
        );
        assert_ne!(
            receiver.sign("482913", 1, "n"),
            receiver.sign("482913", 2, "n")
        );
    }
}
