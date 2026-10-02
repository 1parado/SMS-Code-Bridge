//! 接收侧校验：解包、AES-256-GCM 认证解密、重放与去重（协议 v2）。
//!
//! 时间以毫秒时间戳入参注入，便于测试。所有非法消息一律丢弃并记录原因。

use crate::pairing::{hex_decode, to_hex};
use crate::protocol::Message;
use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::collections::{HashMap, VecDeque};

type HmacSha256 = Hmac<Sha256>;

/// GCM IV 长度（字节）。IV 随机生成、每条消息不重复，同时用作重放检测的键。
pub const IV_LEN: usize = 12;
/// GCM 认证标签长度（字节）。
pub const TAG_LEN: usize = 16;

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
    seen_ivs: HashMap<String, i64>,
    recent: VecDeque<(i64, String)>,
}

impl std::fmt::Debug for Receiver {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // 注意：secret 属敏感凭据，Debug 输出中一律脱敏，仅暴露非敏感的运行期计数。
        f.debug_struct("Receiver")
            .field("replay_window_ms", &self.replay_window_ms)
            .field("dedup_window_ms", &self.dedup_window_ms)
            .field("max_packet_bytes", &self.max_packet_bytes)
            .field("seen_ivs_len", &self.seen_ivs.len())
            .field("recent_len", &self.recent.len())
            .field("secret", &"<redacted>")
            .finish()
    }
}

/// 验证码消息的 AAD：把时间戳绑定进认证标签，防止密文与时间戳被拆开重放。
fn code_aad(ts: i64) -> String {
    format!("code|{ts}")
}

impl Receiver {
    pub fn new(secret: [u8; 32]) -> Self {
        Self {
            secret,
            replay_window_ms: DEFAULT_REPLAY_WINDOW_MS,
            dedup_window_ms: DEFAULT_DEDUP_WINDOW_MS,
            max_packet_bytes: DEFAULT_MAX_PACKET_BYTES,
            seen_ivs: HashMap::new(),
            recent: VecDeque::new(),
        }
    }

    pub fn with_windows(mut self, replay_window_ms: i64, dedup_window_ms: i64) -> Self {
        self.replay_window_ms = replay_window_ms;
        self.dedup_window_ms = dedup_window_ms;
        self
    }

    /// 加密一帧验证码，返回 GCM 密文（含认证标签）的十六进制。
    /// IV 由调用方随机生成；测试与跨端固定向量使用固定 IV。
    pub fn seal(code: &str, ts: i64, iv: &[u8; IV_LEN], secret: &[u8; 32]) -> String {
        let ciphertext = Self::encrypt(code.as_bytes(), ts, iv, secret)
            .expect("GCM 加密在密钥与 IV 长度固定时不会失败");
        to_hex(&ciphertext)
    }

    fn encrypt(
        plain: &[u8],
        ts: i64,
        iv: &[u8; IV_LEN],
        secret: &[u8; 32],
    ) -> Result<Vec<u8>, aes_gcm::Error> {
        let cipher = Aes256Gcm::new_from_slice(secret).expect("AES-256 密钥长度固定");
        cipher.encrypt(
            Nonce::from_slice(iv),
            Payload {
                msg: plain,
                aad: code_aad(ts).as_bytes(),
            },
        )
    }

    /// 认证解密：GCM 标签或 AAD 不符时返回 None。
    fn decrypt(
        ciphertext: &[u8],
        ts: i64,
        iv: &[u8; IV_LEN],
        secret: &[u8; 32],
    ) -> Option<Vec<u8>> {
        let cipher = Aes256Gcm::new_from_slice(secret).expect("AES-256 密钥长度固定");
        cipher
            .decrypt(
                Nonce::from_slice(iv),
                Payload {
                    msg: ciphertext,
                    aad: code_aad(ts).as_bytes(),
                },
            )
            .ok()
    }

    /// 校验解绑请求的认证 MAC：HMAC(secret, "unpair|{ts}")，且时间戳在重放窗口内。
    pub fn verify_unpair(&self, ts: i64, mac_hex: &str, now_ms: i64) -> bool {
        if (now_ms - ts).abs() > self.replay_window_ms {
            return false;
        }
        // 与 KeyInit::new_from_slice 区分，避免二义性
        let mut mac =
            <HmacSha256 as Mac>::new_from_slice(&self.secret).expect("HMAC 接受任意长度密钥");
        mac.update(format!("unpair|{ts}").as_bytes());
        to_hex(&mac.finalize().into_bytes()) == mac_hex.to_ascii_lowercase()
    }

    /// 处理一帧数据；通过全部校验时返回验证码明文。
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
            ts, iv_hex, ct_hex, ..
        } = message
        else {
            return Err(RejectReason::UnexpectedType);
        };

        // 先认证（GCM），再校验时间窗口：不可信字段不参与任何放行判断
        let Some(iv_bytes) = hex_decode(&iv_hex) else {
            return Err(RejectReason::Malformed);
        };
        let Ok(iv) = <[u8; IV_LEN]>::try_from(iv_bytes.as_slice()) else {
            return Err(RejectReason::Malformed);
        };
        let Some(ciphertext) = hex_decode(&ct_hex) else {
            return Err(RejectReason::Malformed);
        };
        if ciphertext.len() < TAG_LEN {
            return Err(RejectReason::Malformed);
        }
        let Some(plaintext) = Self::decrypt(&ciphertext, ts, &iv, &self.secret) else {
            return Err(RejectReason::BadMac);
        };
        let Ok(code) = String::from_utf8(plaintext) else {
            return Err(RejectReason::Malformed);
        };

        if (now_ms - ts).abs() > self.replay_window_ms {
            return Err(RejectReason::Expired);
        }
        if self.seen_ivs.contains_key(&iv_hex) {
            return Err(RejectReason::Replay);
        }
        if self.is_duplicate(&code, now_ms) {
            return Err(RejectReason::Duplicate);
        }

        self.forget_expired(now_ms);
        self.seen_ivs.insert(iv_hex, ts);
        self.recent.push_back((now_ms, code.clone()));
        Ok(code)
    }

    fn is_duplicate(&self, code: &str, now_ms: i64) -> bool {
        self.recent.iter().any(|(ts, recent)| {
            *recent == code && now_ms.saturating_sub(*ts) <= self.dedup_window_ms
        })
    }

    fn forget_expired(&mut self, now_ms: i64) {
        self.seen_ivs
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
    use crate::protocol::PROTOCOL_VERSION;

    /// shared/PROTOCOL.md 约定的夹具测试密钥：000102...1f。
    const SECRET: [u8; 32] = [
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
        0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d,
        0x1e, 0x1f,
    ];

    fn receiver() -> Receiver {
        Receiver::new(SECRET).with_windows(300_000, 10_000)
    }

    fn frame(
        _receiver: &Receiver,
        code: &str,
        ts: i64,
        iv: &[u8; IV_LEN],
        ct_hex: Option<&str>,
    ) -> Vec<u8> {
        let ct_hex = ct_hex
            .map(str::to_string)
            .unwrap_or_else(|| Receiver::seal(code, ts, iv, &SECRET));
        Message::Code {
            v: PROTOCOL_VERSION,
            ts,
            iv_hex: to_hex(iv),
            ct_hex,
        }
        .to_json()
        .expect("序列化失败")
        .into_bytes()
    }

    #[test]
    fn valid_message_accepted() {
        let mut receiver = receiver();
        let raw = frame(&receiver, "482913", 1_000, &[1u8; IV_LEN], None);
        assert_eq!(receiver.handle(&raw, 1_100).expect("应通过"), "482913");
    }

    /// 跨端固定向量：shared/testdata/code_message.json 的密文必须能被解出。
    #[test]
    fn shared_fixture_decrypts_to_expected_code() {
        let raw = include_str!("../../shared/testdata/code_message.json");
        let mut receiver = receiver();
        assert_eq!(
            receiver
                .handle(raw.as_bytes(), 1_757_337_600_000)
                .expect("夹具应通过"),
            "482913"
        );
    }

    /// 跨端固定向量：给定密钥、IV、AAD 与明文，密文必须与夹具逐字节一致
    /// （Android 端 CryptoTest 使用同一向量）。
    #[test]
    fn seal_matches_shared_fixture() {
        let iv = hex_decode("000102030405060708090a0b").expect("夹具 IV 非法");
        let iv: [u8; IV_LEN] = iv.try_into().expect("IV 长度");
        assert_eq!(
            Receiver::seal("482913", 1_757_337_600_000, &iv, &SECRET),
            "733ae422f4d65d9adc9f1e88ba1812b0a0ebc425b984"
        );
    }

    #[test]
    fn tampered_ciphertext_rejected() {
        let mut receiver = receiver();
        let mut ciphertext = hex_decode(&Receiver::seal("482913", 1_000, &[1u8; IV_LEN], &SECRET))
            .expect("解密失败");
        let last = ciphertext.len() - 1;
        ciphertext[last] ^= 0x01;
        let raw = frame(
            &receiver,
            "",
            1_000,
            &[1u8; IV_LEN],
            Some(&to_hex(&ciphertext)),
        );
        assert_eq!(receiver.handle(&raw, 1_100), Err(RejectReason::BadMac));
    }

    #[test]
    fn mac_from_other_secret_rejected() {
        let mut receiver = receiver();
        // 用其他密钥加密的密文，GCM 认证必然失败
        let ct = Receiver::seal("482913", 1_000, &[1u8; IV_LEN], &[9u8; 32]);
        let raw = frame(&receiver, "482913", 1_000, &[1u8; IV_LEN], Some(&ct));
        assert_eq!(receiver.handle(&raw, 1_100), Err(RejectReason::BadMac));
    }

    /// 时间戳被篡改时 AAD 校验必须失败（防止密文与时间戳拆开重放）。
    #[test]
    fn shifted_timestamp_rejected() {
        let mut receiver = receiver();
        // 用 ts=1000 计算的密文，装进声称 ts=2000 的信封 → AAD 不符
        let ct = Receiver::seal("482913", 1_000, &[1u8; IV_LEN], &SECRET);
        let raw = Message::Code {
            v: PROTOCOL_VERSION,
            ts: 2_000,
            iv_hex: to_hex(&[1u8; IV_LEN]),
            ct_hex: ct,
        }
        .to_json()
        .expect("序列化失败")
        .into_bytes();
        assert_eq!(receiver.handle(&raw, 2_100), Err(RejectReason::BadMac));
    }

    #[test]
    fn replay_outside_window_rejected() {
        let mut receiver = Receiver::new(SECRET).with_windows(1_000, 10_000);
        let raw = frame(&receiver, "482913", 1_000, &[1u8; IV_LEN], None);
        assert_eq!(receiver.handle(&raw, 5_000), Err(RejectReason::Expired));
    }

    #[test]
    fn same_iv_rejected_as_replay() {
        let mut receiver = receiver();
        let first = frame(&receiver, "482913", 1_000, &[1u8; IV_LEN], None);
        let second = frame(&receiver, "482914", 1_100, &[1u8; IV_LEN], None);
        assert!(receiver.handle(&first, 1_050).is_ok());
        assert_eq!(receiver.handle(&second, 1_150), Err(RejectReason::Replay));
    }

    #[test]
    fn duplicate_code_within_window_dropped() {
        let mut receiver = receiver();
        let first = frame(&receiver, "482913", 1_000, &[1u8; IV_LEN], None);
        let second = frame(&receiver, "482913", 1_500, &[2u8; IV_LEN], None);
        assert!(receiver.handle(&first, 1_050).is_ok());
        assert_eq!(
            receiver.handle(&second, 1_550),
            Err(RejectReason::Duplicate)
        );
    }

    #[test]
    fn same_code_after_dedup_window_accepted() {
        let mut receiver = Receiver::new(SECRET).with_windows(300_000, 1_000);
        let first = frame(&receiver, "482913", 1_000, &[1u8; IV_LEN], None);
        let second = frame(&receiver, "482913", 5_000, &[2u8; IV_LEN], None);
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
        // IV 与密文长度/编码非法
        assert_eq!(
            receiver.handle(
                Message::Code {
                    v: PROTOCOL_VERSION,
                    ts: 1,
                    iv_hex: "00".into(),
                    ct_hex: "00".into(),
                }
                .to_json()
                .expect("序列化失败")
                .as_bytes(),
                0
            ),
            Err(RejectReason::Malformed)
        );
    }

    #[test]
    fn wrong_message_type_rejected() {
        let mut receiver = receiver();
        let raw = Message::PairResponse {
            v: PROTOCOL_VERSION,
            ok: true,
            session_id: None,
        }
        .to_json()
        .expect("序列化失败")
        .into_bytes();
        assert_eq!(receiver.handle(&raw, 0), Err(RejectReason::UnexpectedType));
    }

    /// 跨端固定向量：shared/testdata/unpair.json 的 mac 必须通过校验
    /// （Android 端 PairingClientTest 使用同一向量）。
    #[test]
    fn unpair_mac_matches_shared_fixture() {
        let receiver = receiver();
        assert!(receiver.verify_unpair(
            1_757_337_600_000,
            "86279a29238265aa8ee995e3909b0ab5773cb3df5d84f1c666605d0483eb19c6",
            1_757_337_600_000
        ));
        assert!(!receiver.verify_unpair(1_757_337_600_000, "deadbeef", 1_757_337_600_000));
        assert!(
            !receiver.verify_unpair(
                1_757_337_600_000,
                "86279a29238265aa8ee995e3909b0ab5773cb3df5d84f1c666605d0483eb19c6",
                1_757_337_600_000 + 300_001
            ),
            "时间戳出窗应拒绝"
        );
    }
}
