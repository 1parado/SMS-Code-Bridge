//! 两端共享的局域网消息协议。
//!
//! 安全约束：消息体只携带必要字段，**绝不包含短信原文、手机号或发件人**。
//! 夹具见 `shared/testdata/`，与 Android 端共用，保证两端解析行为一致。

use serde::{Deserialize, Serialize};

/// 协议版本。版本不一致的消息一律拒绝处理。
pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Message {
    /// 手机 → 电脑：携带配对码的配对请求
    PairRequest {
        v: u32,
        device_id: String,
        code: String,
        /// 手机生成的随机盐（hex）。两端各自用 code + salt 派生同一会话密钥，密钥本身不上网传输。
        salt_hex: String,
    },
    /// 电脑 → 手机：配对结果
    PairResponse {
        v: u32,
        ok: bool,
        session_id: Option<String>,
    },
    /// 手机 → 电脑：验证码（只含验证码本身）
    Code {
        v: u32,
        code: String,
        ts: i64,
        nonce: String,
        mac: String,
    },
    /// 手机 → 电脑：心跳保活（仅含版本与设备号，绝不携带敏感信息）
    Heartbeat { v: u32, device_id: String },
    /// 手机 → 电脑（广播）：谁在线？
    DiscoveryRequest { v: u32, device_id: String },
    /// 电脑 → 手机：我在这里（仅含发现所需字段）
    DiscoveryResponse {
        v: u32,
        device_id: String,
        name: String,
        port: u16,
    },
    /// 手机 → 电脑：主动解绑（清密钥 + 清配对）
    Unpair { v: u32, device_id: String },
}

impl Message {
    pub fn version(&self) -> u32 {
        match self {
            Message::PairRequest { v, .. }
            | Message::PairResponse { v, .. }
            | Message::Code { v, .. }
            | Message::Heartbeat { v, .. }
            | Message::DiscoveryRequest { v, .. }
            | Message::DiscoveryResponse { v, .. }
            | Message::Unpair { v, .. } => *v,
        }
    }

    /// 版本不匹配的消息不得进入后续处理流程。
    pub fn is_supported(&self) -> bool {
        self.version() == PROTOCOL_VERSION
    }

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn from_json(text: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pair_request_roundtrip() {
        let raw = include_str!("../../shared/testdata/pair_request.json");
        let message = Message::from_json(raw).expect("解析配对请求失败");
        match &message {
            Message::PairRequest {
                code, device_id, salt_hex, ..
            } => {
                assert_eq!(code, "123456");
                assert_eq!(device_id, "device-0001");
                assert_eq!(salt_hex, "30313233343536373839616263646566");
            }
            other => panic!("消息类型不符: {other:?}"),
        }
        assert!(message.is_supported());
        let encoded = message.to_json().expect("序列化失败");
        assert_eq!(Message::from_json(&encoded).expect("回读失败"), message);
    }

    #[test]
    fn pair_response_roundtrip() {
        let raw = include_str!("../../shared/testdata/pair_response.json");
        let message = Message::from_json(raw).expect("解析配对响应失败");
        match &message {
            Message::PairResponse { ok, session_id, .. } => {
                assert!(ok);
                assert_eq!(session_id.as_deref(), Some("s-0001"));
            }
            other => panic!("消息类型不符: {other:?}"),
        }
    }

    #[test]
    fn code_message_roundtrip() {
        let raw = include_str!("../../shared/testdata/code_message.json");
        let message = Message::from_json(raw).expect("解析验证码消息失败");
        match &message {
            Message::Code {
                code, ts, nonce, ..
            } => {
                assert_eq!(code, "482913");
                assert_eq!(*ts, 1_757_337_600_000);
                assert_eq!(nonce, "n-0001");
            }
            other => panic!("消息类型不符: {other:?}"),
        }
        let encoded = message.to_json().expect("序列化失败");
        assert_eq!(Message::from_json(&encoded).expect("回读失败"), message);
    }

    #[test]
    fn discovery_request_roundtrip() {
        let raw = include_str!("../../shared/testdata/discovery_request.json");
        let message = Message::from_json(raw).expect("解析发现请求失败");
        match &message {
            Message::DiscoveryRequest { device_id, .. } => assert_eq!(device_id, "device-0001"),
            other => panic!("消息类型不符: {other:?}"),
        }
        let encoded = message.to_json().expect("序列化失败");
        assert_eq!(Message::from_json(&encoded).expect("回读失败"), message);
    }

    #[test]
    fn discovery_response_roundtrip() {
        let raw = include_str!("../../shared/testdata/discovery_response.json");
        let message = Message::from_json(raw).expect("解析发现响应失败");
        match &message {
            Message::DiscoveryResponse {
                device_id,
                name,
                port,
                ..
            } => {
                assert_eq!(device_id, "pc-0001");
                assert_eq!(name, "PC-0001");
                assert_eq!(*port, 45876);
            }
            other => panic!("消息类型不符: {other:?}"),
        }
        let encoded = message.to_json().expect("序列化失败");
        assert_eq!(Message::from_json(&encoded).expect("回读失败"), message);
    }

    #[test]
    fn unknown_field_is_ignored() {
        let raw = include_str!("../../shared/testdata/code_message_unknown_field.json");
        let message = Message::from_json(raw).expect("未知字段应当被忽略");
        let expected = Message::from_json(include_str!("../../shared/testdata/code_message.json"))
            .expect("解析基准夹具失败");
        assert_eq!(message, expected);
    }

    #[test]
    fn version_mismatch_rejected() {
        let raw = include_str!("../../shared/testdata/code_message_version_mismatch.json");
        let message = Message::from_json(raw).expect("结构合法，应能解析");
        assert!(!message.is_supported());
    }

    #[test]
    fn code_message_never_carries_sensitive_fields() {
        let raw = include_str!("../../shared/testdata/code_message.json");
        let message = Message::from_json(raw).expect("解析失败");
        let encoded = message.to_json().expect("序列化失败");
        for forbidden in ["body", "sender", "phone", "address", "content"] {
            assert!(
                !encoded.contains(forbidden),
                "消息体不应出现字段 {forbidden}: {encoded}"
            );
        }
    }

    #[test]
    fn heartbeat_roundtrip() {
        let raw = include_str!("../../shared/testdata/heartbeat.json");
        let message = Message::from_json(raw).expect("解析心跳失败");
        match &message {
            Message::Heartbeat { v, device_id } => {
                assert_eq!(*v, PROTOCOL_VERSION);
                assert_eq!(device_id, "device-0001");
            }
            other => panic!("消息类型不符: {other:?}"),
        }
        assert!(message.is_supported());
        let encoded = message.to_json().expect("序列化失败");
        assert_eq!(Message::from_json(&encoded).expect("回读失败"), message);
    }

    #[test]
    fn unpair_roundtrip() {
        let raw = include_str!("../../shared/testdata/unpair.json");
        let message = Message::from_json(raw).expect("解析解绑消息失败");
        match &message {
            Message::Unpair { v, device_id } => {
                assert_eq!(*v, PROTOCOL_VERSION);
                assert_eq!(device_id, "device-0001");
            }
            other => panic!("消息类型不符: {other:?}"),
        }
        let encoded = message.to_json().expect("序列化失败");
        assert_eq!(Message::from_json(&encoded).expect("回读失败"), message);
    }

    #[test]
    fn heartbeat_and_unpair_never_carry_sensitive_fields() {
        let heartbeat = Message::Heartbeat {
            v: PROTOCOL_VERSION,
            device_id: "device-0001".into(),
        };
        let unpair = Message::Unpair {
            v: PROTOCOL_VERSION,
            device_id: "device-0001".into(),
        };
        let request = Message::DiscoveryRequest {
            v: PROTOCOL_VERSION,
            device_id: "device-0001".into(),
        };
        let response = Message::DiscoveryResponse {
            v: PROTOCOL_VERSION,
            device_id: "pc-0001".into(),
            name: "PC-0001".into(),
            port: 45876,
        };
        for (name, message) in [
            ("heartbeat", heartbeat),
            ("unpair", unpair),
            ("discovery_request", request),
            ("discovery_response", response),
        ] {
            let encoded = message.to_json().expect("序列化失败");
            for forbidden in [
                "body", "sender", "phone", "address", "content", "secret", "code",
            ] {
                assert!(
                    !encoded.contains(forbidden),
                    "{name} 不应出现字段 {forbidden}: {encoded}"
                );
            }
        }
    }
}
