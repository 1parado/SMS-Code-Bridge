//! 配对：一次性配对码、有效期与会话密钥派生。
//!
//! 时间以毫秒时间戳入参注入，便于测试；盐值与配对码均来自系统随机源。

use hmac::{Hmac, Mac};
use rand::Rng;
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};

type HmacSha256 = Hmac<Sha256>;

/// 配对码默认有效期：2 分钟。
pub const DEFAULT_TTL_MS: i64 = 120_000;

/// 盐值长度。
const SALT_LEN: usize = 16;

/// 当前毫秒时间戳（时钟异常时退化为 0）。
pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0)
}

/// 生成 6 位数字配对码。
pub fn generate_code() -> String {
    let mut rng = rand::thread_rng();
    format!("{:06}", rng.gen_range(0..1_000_000u32))
}

/// 由配对码与盐值派生 32 字节会话密钥（HMAC-SHA256）。
///
/// 会话密钥从不通过网络传输，两端各自派生。
pub fn derive_secret(code: &str, salt: &[u8]) -> [u8; 32] {
    let mut mac = HmacSha256::new_from_slice(code.as_bytes()).expect("HMAC 接受任意长度密钥");
    mac.update(salt);
    let bytes = mac.finalize().into_bytes();
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&bytes);
    secret
}

pub fn to_hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

/// 一次配对挑战：一次性、带有效期。
#[derive(Debug, Clone)]
pub struct Challenge {
    pub code: String,
    pub salt: Vec<u8>,
    pub created_at_ms: i64,
    consumed: bool,
}

/// 配对成功后建立的会话。
#[derive(Debug, Clone, PartialEq)]
pub struct Session {
    pub session_id: String,
    pub secret: [u8; 32],
}

/// 配对管理：负责发放配对码并校验来自手机端的应答。
pub struct PairingManager {
    ttl_ms: i64,
    challenge: Option<Challenge>,
}

impl PairingManager {
    pub fn new(ttl_ms: i64) -> Self {
        Self {
            ttl_ms,
            challenge: None,
        }
    }

    /// 发放一个新的配对挑战，返回 (配对码, 盐值)。再次调用会作废旧挑战。
    pub fn issue(&mut self, now_ms: i64) -> (String, Vec<u8>) {
        let code = generate_code();
        let mut rng = rand::thread_rng();
        let salt: Vec<u8> = (0..SALT_LEN).map(|_| rng.gen()).collect();

        self.challenge = Some(Challenge {
            code: code.clone(),
            salt: salt.clone(),
            created_at_ms: now_ms,
            consumed: false,
        });
        (code, salt)
    }

    /// 校验配对码：错误、过期、已使用过一律拒绝。成功则消费该挑战并返回会话。
    pub fn verify(&mut self, code: &str, now_ms: i64) -> Option<Session> {
        let challenge = self.challenge.as_mut()?;
        if challenge.consumed {
            return None;
        }
        if now_ms.saturating_sub(challenge.created_at_ms) > self.ttl_ms {
            return None;
        }
        if challenge.code != code {
            return None;
        }

        challenge.consumed = true;
        let secret = derive_secret(&challenge.code, &challenge.salt);
        Some(Session {
            session_id: to_hex(&secret[..8]),
            secret,
        })
    }

    /// 作废当前挑战（用于取消配对）。
    pub fn reset(&mut self) {
        self.challenge = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_code_is_six_digits() {
        for _ in 0..50 {
            let code = generate_code();
            assert_eq!(code.len(), 6, "配对码应为 6 位: {code}");
            assert!(
                code.chars().all(|c| c.is_ascii_digit()),
                "应全为数字: {code}"
            );
        }
    }

    #[test]
    fn wrong_pairing_code_rejected() {
        let mut manager = PairingManager::new(DEFAULT_TTL_MS);
        let (code, _salt) = manager.issue(1_000);
        let wrong = if code == "000000" { "111111" } else { "000000" };
        assert!(manager.verify(wrong, 1_100).is_none());
        // 错误尝试不应消费掉挑战
        assert!(manager.verify(&code, 1_100).is_some());
    }

    #[test]
    fn expired_pairing_code_rejected() {
        let mut manager = PairingManager::new(DEFAULT_TTL_MS);
        let (code, _salt) = manager.issue(1_000);
        assert!(manager.verify(&code, 1_000 + DEFAULT_TTL_MS + 1).is_none());
    }

    #[test]
    fn pairing_code_cannot_be_reused() {
        let mut manager = PairingManager::new(DEFAULT_TTL_MS);
        let (code, _salt) = manager.issue(1_000);
        assert!(manager.verify(&code, 1_100).is_some());
        assert!(manager.verify(&code, 1_200).is_none(), "配对码必须一次性");
    }

    #[test]
    fn verify_without_challenge_returns_none() {
        let mut manager = PairingManager::new(DEFAULT_TTL_MS);
        assert!(manager.verify("123456", 0).is_none());
    }

    #[test]
    fn key_derivation_is_deterministic_and_sufficient_length() {
        let a = derive_secret("123456", b"salt-0001");
        let b = derive_secret("123456", b"salt-0001");
        let c = derive_secret("123456", b"salt-0002");
        assert_eq!(a, b, "相同输入应派生相同密钥");
        assert_ne!(a, c, "不同盐值应派生不同密钥");
        assert_eq!(a.len(), 32);
    }

    #[test]
    fn reset_invalidates_pending_challenge() {
        let mut manager = PairingManager::new(DEFAULT_TTL_MS);
        let (code, _salt) = manager.issue(1_000);
        manager.reset();
        assert!(manager.verify(&code, 1_100).is_none());
    }

    #[test]
    fn to_hex_encodes_as_lowercase() {
        assert_eq!(to_hex(&[0x00, 0x0f, 0xff]), "000fff");
    }
}
