//! 本地配置：加载、保存与默认值。
//!
//! 设计约束：
//! - 配置文件缺失、损坏或字段不全时，一律回退到默认值，绝不因配置问题崩溃
//! - 路径可注入，方便测试（不依赖平台相关行为）

use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// 默认 UDP 监听端口。
pub const DEFAULT_PORT: u16 = 45876;
/// 默认历史保留条数。
pub const DEFAULT_HISTORY_LIMIT: usize = 50;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    /// 监听端口
    pub port: u16,
    /// 收到验证码后自动写入剪贴板
    pub auto_copy: bool,
    /// 是否弹出轻量提示
    pub notify: bool,
    /// 本地历史最多保留条数
    pub history_limit: usize,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: DEFAULT_PORT,
            auto_copy: true,
            notify: true,
            history_limit: DEFAULT_HISTORY_LIMIT,
        }
    }
}

impl Config {
    /// 默认配置文件路径：`<用户配置目录>/sms-code-bridge/config.json`
    pub fn config_path() -> Option<PathBuf> {
        dirs::config_dir().map(|dir| dir.join("sms-code-bridge").join("config.json"))
    }

    /// 从默认路径加载配置。
    pub fn load() -> Self {
        Self::load_from(Self::config_path().as_deref())
    }

    /// 从指定路径加载；路径为空、文件不存在或内容非法时回退默认值。
    pub fn load_from(path: Option<&Path>) -> Self {
        let Some(path) = path else {
            return Self::default();
        };
        let Ok(content) = fs::read_to_string(path) else {
            return Self::default();
        };
        serde_json::from_str(&content).unwrap_or_default()
    }

    /// 保存到默认路径（自动创建父目录）。
    pub fn save(&self) -> io::Result<()> {
        match Self::config_path() {
            Some(path) => self.save_to(&path),
            None => Err(io::Error::new(
                io::ErrorKind::NotFound,
                "无法定位用户配置目录",
            )),
        }
    }

    /// 保存到指定路径（自动创建父目录）。
    pub fn save_to(&self, path: &Path) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let content =
            serde_json::to_string_pretty(self).map_err(|err| io::Error::other(err.to_string()))?;
        fs::write(path, content)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    fn temp_dir(tag: &str) -> PathBuf {
        static COUNTER: AtomicU32 = AtomicU32::new(0);
        let seq = COUNTER.fetch_add(1, Ordering::SeqCst);
        let dir = std::env::temp_dir().join(format!(
            "sms-code-bridge-test-{}-{}-{}",
            tag,
            std::process::id(),
            seq
        ));
        fs::create_dir_all(&dir).expect("创建临时目录失败");
        dir
    }

    #[test]
    fn default_config_has_expected_values() {
        let config = Config::default();
        assert_eq!(config.port, DEFAULT_PORT);
        assert!(config.auto_copy);
        assert!(config.notify);
        assert_eq!(config.history_limit, DEFAULT_HISTORY_LIMIT);
    }

    #[test]
    fn load_from_none_returns_default() {
        assert_eq!(Config::load_from(None), Config::default());
    }

    #[test]
    fn load_missing_file_returns_default() {
        let dir = temp_dir("missing");
        let path = dir.join("config.json");
        assert!(!path.exists());
        assert_eq!(Config::load_from(Some(&path)), Config::default());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn malformed_json_falls_back_to_default() {
        let dir = temp_dir("malformed");
        let path = dir.join("config.json");
        fs::write(&path, "{ not a valid json").expect("写入失败");
        assert_eq!(Config::load_from(Some(&path)), Config::default());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn save_then_load_roundtrip() {
        let dir = temp_dir("roundtrip");
        let path = dir.join("nested").join("config.json");
        let config = Config {
            port: 50000,
            auto_copy: false,
            notify: false,
            history_limit: 10,
        };
        config.save_to(&path).expect("保存失败");
        assert!(path.exists());
        assert_eq!(Config::load_from(Some(&path)), config);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn partial_config_missing_fields_use_defaults() {
        let dir = temp_dir("partial");
        let path = dir.join("config.json");
        fs::write(&path, r#"{"port": 49000}"#).expect("写入失败");
        let loaded = Config::load_from(Some(&path));
        assert_eq!(loaded.port, 49000);
        assert_eq!(loaded.auto_copy, Config::default().auto_copy);
        assert_eq!(loaded.notify, Config::default().notify);
        assert_eq!(loaded.history_limit, Config::default().history_limit);
        fs::remove_dir_all(&dir).ok();
    }
}
