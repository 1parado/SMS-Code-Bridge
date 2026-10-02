//! 配对：一次性配对码、有效期与会话密钥派生（协议 v2）。
//!
//! 派生链（借鉴 wx-ime-sdk「凭据不上网」原则）：
//! 1. 手机生成随机盐，用配对码计算证明 `proof = PBKDF2-HMAC-SHA256(code, salt)`
//!    并随 PairRequest 发来——**配对码本身绝不上网**；
//! 2. 电脑用本地挑战中的配对码复算同一证明，一致即证明手机持有配对码；
//! 3. 两端各自派生会话密钥 `secret = HMAC-SHA256(proof, "session-v2")`，
//!    密钥从不上网；嗅探者拿到证明后仍需爆破被拉伸的 6 位配对码。
//!
//! 时间以毫秒时间戳入参注入，便于测试。

use hmac::{Hmac, Mac};
use pbkdf2::pbkdf2_hmac;
use rand::Rng;
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};

type HmacSha256 = Hmac<Sha256>;

/// 配对码默认有效期：2 分钟。
pub const DEFAULT_TTL_MS: i64 = 120_000;

/// 配对盐值长度：手机生成、随 PairRequest 发来，电脑只校验长度不自行生成。
pub const SALT_LEN: usize = 16;

/// 配对证明的 PBKDF2 迭代次数：把 6 位配对码的离线爆破代价抬高约 5 个数量级。
pub const PROOF_ITERATIONS: u32 = 100_000;

/// 同一挑战允许的最大失败尝试次数：超过后挑战作废，需重新生成配对码。
pub const MAX_ATTEMPTS: u32 = 5;

/// 会话密钥派生标签：与上网传输的证明在密码学上分离。
const SESSION_LABEL: &[u8] = b"sms-code-bridge/session-v2";
/// 会话 ID 派生标签：可公开，用于对端互相确认持有同一密钥。
const SESSION_ID_LABEL: &[u8] = b"session-id";

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

/// 生成 32 字节随机会话密钥（一键配对：电脑端确认后直接生成并单播下发）。
pub fn generate_secret() -> [u8; 32] {
    let mut rng = rand::thread_rng();
    rng.gen()
}

/// 配对证明：PBKDF2-HMAC-SHA256(配对码, 盐)。
pub fn derive_proof(code: &str, salt: &[u8], iterations: u32) -> [u8; 32] {
    let mut out = [0u8; 32];
    pbkdf2_hmac::<Sha256>(code.as_bytes(), salt, iterations, &mut out);
    out
}

/// 会话密钥：由证明派生（HMAC-SHA256），从不上网传输，两端各自计算。
pub fn derive_secret(proof: &[u8; 32]) -> [u8; 32] {
    let bytes = hmac_bytes(proof, SESSION_LABEL);
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&bytes);
    secret
}

/// 会话 ID：HMAC(secret, "session-id") 前 8 字节。可公开；对端能算出同一个值
/// 即证明其持有同一会话密钥（PairResponse 的防伪造依据）。
pub fn session_id(secret: &[u8; 32]) -> String {
    to_hex(&hmac_bytes(secret, SESSION_ID_LABEL)[..8])
}

fn hmac_bytes(key: &[u8], message: &[u8]) -> [u8; 32] {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC 接受任意长度密钥");
    mac.update(message);
    let bytes = mac.finalize().into_bytes();
    let mut out = [0u8; 32];
    out.copy_from_slice(&bytes);
    out
}

pub fn to_hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

/// 十六进制转字节；长度非偶数或含非法字符时返回 None。
pub fn hex_decode(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .ok()
}

/// 一次配对挑战：一次性、带有效期、限失败次数。
pub struct Challenge {
    code: String,
    created_at_ms: i64,
    attempts: u32,
    consumed: bool,
}

impl std::fmt::Debug for Challenge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // 配对码属于敏感凭据，Debug 输出一律脱敏
        f.debug_struct("Challenge")
            .field("code", &"[REDACTED]")
            .field("created_at_ms", &self.created_at_ms)
            .field("attempts", &self.attempts)
            .field("consumed", &self.consumed)
            .finish()
    }
}

/// 配对成功后建立的会话。
#[derive(Clone, PartialEq)]
pub struct Session {
    pub session_id: String,
    pub secret: [u8; 32],
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // 会话密钥属于敏感凭据，Debug 输出一律脱敏
        f.debug_struct("Session")
            .field("session_id", &self.session_id)
            .field("secret", &"<redacted>")
            .finish()
    }
}

/// 配对管理：负责发放配对码并校验来自手机端的证明。
pub struct PairingManager {
    ttl_ms: i64,
    proof_iterations: u32,
    challenge: Option<Challenge>,
}

impl PairingManager {
    pub fn new(ttl_ms: i64) -> Self {
        Self::with_iterations(ttl_ms, PROOF_ITERATIONS)
    }

    /// 测试可注入低迭代次数；生产走 [`PROOF_ITERATIONS`]。
    pub fn with_iterations(ttl_ms: i64, proof_iterations: u32) -> Self {
        Self {
            ttl_ms,
            proof_iterations,
            challenge: None,
        }
    }

    /// 发放一个新的配对挑战，返回配对码。盐值由手机端生成并随请求带来，电脑不再自行生成。
    pub fn issue(&mut self, now_ms: i64) -> String {
        let code = generate_code();
        self.challenge = Some(Challenge {
            code: code.clone(),
            created_at_ms: now_ms,
            attempts: 0,
            consumed: false,
        });
        code
    }

    /// 校验配对证明：错误、过期、已使用过、失败次数耗尽一律拒绝。
    /// 成功则消费该挑战并返回会话。
    pub fn verify(&mut self, proof_hex: &str, salt: &[u8], now_ms: i64) -> Option<Session> {
        let challenge = self.challenge.as_mut()?;
        if challenge.consumed {
            return None;
        }
        if now_ms.saturating_sub(challenge.created_at_ms) > self.ttl_ms {
            return None;
        }
        if salt.len() != SALT_LEN {
            return None;
        }
        if challenge.attempts >= MAX_ATTEMPTS {
            challenge.consumed = true;
            return None;
        }

        let proof = derive_proof(&challenge.code, salt, self.proof_iterations);
        if to_hex(&proof) != proof_hex.to_ascii_lowercase() {
            challenge.attempts += 1;
            if challenge.attempts >= MAX_ATTEMPTS {
                // 次数耗尽，挑战作废：防止对 6 位配对码在线爆破
                challenge.consumed = true;
            }
            return None;
        }

        challenge.consumed = true;
        let secret = derive_secret(&proof);
        Some(Session {
            session_id: session_id(&secret),
            secret,
        })
    }

    /// 作废当前挑战（用于取消配对）。
    pub fn reset(&mut self) {
        self.challenge = None;
    }

    /// 当前挑战是否仍然有效（存在、未消费、未过期、未超次）。
    /// 用于托盘「显示配对码」判断是否需要重新发放。
    pub fn has_active_challenge(&self, now_ms: i64) -> bool {
        match &self.challenge {
            Some(challenge) => {
                !challenge.consumed
                    && challenge.attempts < MAX_ATTEMPTS
                    && now_ms.saturating_sub(challenge.created_at_ms) <= self.ttl_ms
            }
            None => false,
        }
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

    /// PBKDF2-HMAC-SHA256 的 RFC 7914 已知向量，锁定派生算法本身。
    #[test]
    fn pbkdf2_matches_rfc7914_vectors() {
        assert_eq!(
            to_hex(&derive_proof("password", b"salt", 1)),
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
        );
        assert_eq!(
            to_hex(&derive_proof("password", b"salt", 2)),
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"
        );
        assert_eq!(
            to_hex(&derive_proof("password", b"salt", 4096)),
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"
        );
    }

    /// 跨端固定向量：shared/testdata/pair_request.json 的 proof_hex 即由
    /// code="123456" + 夹具盐值用生产迭代次数算出（Android 端有同一断言）。
    #[test]
    fn proof_matches_shared_fixture() {
        let salt = hex_decode("30313233343536373839616263646566").expect("夹具盐值非法");
        assert_eq!(
            to_hex(&derive_proof("123456", &salt, PROOF_ITERATIONS)),
            "887fc04592766b594b2abe0b9ade1a53da0560339eb3c7d2903f38dd2000116e"
        );
    }

    /// 快速跨端固定向量（低迭代次数）：Android 端 PairingClientTest 使用同一组期望值。
    #[test]
    fn secret_and_session_id_match_android_end() {
        let proof = derive_proof("654321", b"salt-0002", 1_000);
        assert_eq!(
            to_hex(&proof),
            "78e979149d45d82751d78cb463aec93679a2af029de714fa630b961c6fe06da7"
        );
        let secret = derive_secret(&proof);
        assert_eq!(
            to_hex(&secret),
            "973bc978a87938301cfb12ed362128d322e7d72eefc098a2d826f76192d82ab7"
        );
        assert_eq!(session_id(&secret), "b712290aa3746a5a");
    }

    #[test]
    fn key_derivation_is_deterministic_and_sufficient_length() {
        let a = derive_proof("123456", b"salt-0001", 100);
        let b = derive_proof("123456", b"salt-0001", 100);
        let c = derive_proof("123456", b"salt-0002", 100);
        assert_eq!(a, b, "相同输入应派生相同密钥");
        assert_ne!(a, c, "不同盐值应派生不同密钥");
        assert_eq!(a.len(), 32);
        // 证明与会话密钥必须分离：上网的证明不能直接当密钥用
        assert_ne!(a, derive_secret(&a), "会话密钥必须是证明的再派生");
    }

    #[test]
    fn wrong_proof_rejected_and_challenge_kept() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        let code = manager.issue(1_000);
        let wrong_proof = to_hex(&derive_proof("000000", b"0123456789abcdef", 100));
        assert!(manager
            .verify(&wrong_proof, b"0123456789abcdef", 1_100)
            .is_none());
        // 错误尝试不应消费掉挑战
        let right_proof = to_hex(&derive_proof(&code, b"0123456789abcdef", 100));
        assert!(manager
            .verify(&right_proof, b"0123456789abcdef", 1_100)
            .is_some());
    }

    #[test]
    fn expired_pairing_code_rejected() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        manager.issue(1_000);
        let proof = to_hex(&derive_proof("000000", b"0123456789abcdef", 100));
        assert!(manager
            .verify(&proof, b"0123456789abcdef", 1_000 + DEFAULT_TTL_MS + 1)
            .is_none());
    }

    #[test]
    fn pairing_proof_cannot_be_reused() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        let code = manager.issue(1_000);
        let proof = to_hex(&derive_proof(&code, b"0123456789abcdef", 100));
        assert!(manager.verify(&proof, b"0123456789abcdef", 1_100).is_some());
        assert!(
            manager.verify(&proof, b"0123456789abcdef", 1_200).is_none(),
            "配对必须一次性"
        );
    }

    #[test]
    fn invalid_salt_rejected() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        let code = manager.issue(1_000);
        let proof = to_hex(&derive_proof(&code, b"short", 100));
        assert!(manager.verify(&proof, b"short", 1_100).is_none());
        assert!(manager.verify(&proof, b"", 1_100).is_none());
    }

    #[test]
    fn attempts_exhausted_invalidates_challenge() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        let code = manager.issue(1_000);
        let wrong = to_hex(&derive_proof("000000", b"0123456789abcdef", 100));
        for _ in 0..MAX_ATTEMPTS {
            assert!(
                manager.verify(&wrong, b"0123456789abcdef", 1_100).is_none(),
                "错误证明应被拒绝"
            );
        }
        // 次数耗尽后，即使证明正确也必须拒绝
        let right = to_hex(&derive_proof(&code, b"0123456789abcdef", 100));
        assert!(
            manager.verify(&right, b"0123456789abcdef", 1_100).is_none(),
            "失败次数耗尽后挑战应作废"
        );
    }

    #[test]
    fn verify_without_challenge_returns_none() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        assert!(manager.verify("deadbeef", b"0123456789abcdef", 0).is_none());
    }

    #[test]
    fn reset_invalidates_pending_challenge() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        manager.issue(1_000);
        manager.reset();
        let proof = to_hex(&derive_proof("123456", b"0123456789abcdef", 100));
        assert!(manager.verify(&proof, b"0123456789abcdef", 1_100).is_none());
    }

    #[test]
    fn active_challenge_tracking() {
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        // 未发放挑战：无效
        assert!(!manager.has_active_challenge(0));
        // 有效期内：有效
        manager.issue(1_000);
        assert!(manager.has_active_challenge(1_000 + DEFAULT_TTL_MS));
        // 过期后：无效
        assert!(!manager.has_active_challenge(1_000 + DEFAULT_TTL_MS + 1));
        // 消费后：无效
        let mut manager = PairingManager::with_iterations(DEFAULT_TTL_MS, 100);
        let code = manager.issue(1_000);
        let proof = to_hex(&derive_proof(&code, b"0123456789abcdef", 100));
        assert!(manager.verify(&proof, b"0123456789abcdef", 1_100).is_some());
        assert!(!manager.has_active_challenge(1_100));
    }

    #[test]
    fn generated_secret_is_random_and_sufficient_length() {
        let a = generate_secret();
        let b = generate_secret();
        assert_eq!(a.len(), 32);
        assert_ne!(a, b, "随机会话密钥不应重复");
    }

    #[test]
    fn to_hex_encodes_as_lowercase() {
        assert_eq!(to_hex(&[0x00, 0x0f, 0xff]), "000fff");
    }

    #[test]
    fn hex_decode_roundtrip_and_garbage() {
        assert_eq!(
            hex_decode("000fff").as_deref(),
            Some(&[0x00, 0x0f, 0xff][..])
        );
        assert_eq!(hex_decode(""), Some(Vec::new()));
        assert_eq!(hex_decode("0"), None);
        assert_eq!(hex_decode("zz"), None);
    }
}
