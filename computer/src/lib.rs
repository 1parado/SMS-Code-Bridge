//! SMS Code Bridge — Windows 端核心库。
//!
//! 与平台无关的逻辑放在库里，便于单元测试；可执行文件只负责装配。

/// 应用版本，取自 Cargo.toml（两端版本号需保持一致）。
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

pub mod clipboard;
pub mod config;
pub mod connection;
pub mod devices;
pub mod discovery;
pub mod history;
pub mod pairing;
pub mod protocol;
pub mod receiver;
pub mod transport;
#[cfg(windows)]
pub mod tray;

#[cfg(test)]
mod tests {
    use super::APP_VERSION;

    /// 版本号必须与 Cargo.toml 一致，避免「关于」里显示的版本与实际构建不符。
    #[test]
    fn app_version_matches_cargo_toml() {
        let manifest = include_str!("../Cargo.toml");
        let declared = manifest
            .lines()
            .find_map(|line| {
                let rest = line.trim().strip_prefix("version = \"")?;
                rest.strip_suffix('"')
            })
            .expect("Cargo.toml 中应声明 version");
        assert_eq!(APP_VERSION, declared);
    }

    /// 跨端版本号硬锁定：与 shared/version.txt 保持一致，Android 端同样断言。
    #[test]
    fn app_version_matches_shared_version_file() {
        let shared = include_str!("../../shared/version.txt").trim();
        assert_eq!(APP_VERSION, shared);
    }
}
