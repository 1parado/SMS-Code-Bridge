//! 已配对设备的本地持久化。
//!
//! 存储在本机用户配置目录（不入库），文件损坏时退化为空列表而不是崩溃。

use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    /// 会话密钥（十六进制）。仅存本机，绝不上网传输。
    pub secret_hex: String,
    pub paired_at_ms: i64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct DeviceFile {
    devices: Vec<PairedDevice>,
}

pub struct DeviceStore {
    path: PathBuf,
    devices: Vec<PairedDevice>,
}

impl DeviceStore {
    /// 从文件加载；文件不存在或损坏时得到空列表。
    pub fn load(path: PathBuf) -> Self {
        let devices = match fs::read_to_string(&path) {
            Ok(content) => serde_json::from_str::<DeviceFile>(&content)
                .map(|file| file.devices)
                .unwrap_or_default(),
            Err(_) => Vec::new(),
        };
        Self { path, devices }
    }

    pub fn save(&self) -> io::Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let content = serde_json::to_string_pretty(&DeviceFile {
            devices: self.devices.clone(),
        })
        .map_err(|err| io::Error::other(err.to_string()))?;
        fs::write(&self.path, content)
    }

    /// 新增或替换同名设备。
    pub fn add(&mut self, device: PairedDevice) {
        if let Some(existing) = self.devices.iter_mut().find(|item| item.id == device.id) {
            *existing = device;
        } else {
            self.devices.push(device);
        }
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.devices.len();
        self.devices.retain(|device| device.id != id);
        self.devices.len() != before
    }

    pub fn get(&self, id: &str) -> Option<&PairedDevice> {
        self.devices.iter().find(|device| device.id == id)
    }

    pub fn list(&self) -> &[PairedDevice] {
        &self.devices
    }

    /// 一键解绑：清空所有已配对设备。
    pub fn clear(&mut self) {
        self.devices.clear();
    }

    pub fn path(&self) -> &Path {
        &self.path
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
            "sms-code-bridge-devices-{}-{}-{}",
            tag,
            std::process::id(),
            seq
        ));
        fs::create_dir_all(&dir).expect("创建临时目录失败");
        dir
    }

    fn sample(id: &str, name: &str) -> PairedDevice {
        PairedDevice {
            id: id.to_string(),
            name: name.to_string(),
            secret_hex: "aabbccdd".to_string(),
            paired_at_ms: 1_000,
        }
    }

    #[test]
    fn load_missing_file_yields_empty_store() {
        let dir = temp_dir("missing");
        let store = DeviceStore::load(dir.join("devices.json"));
        assert!(store.list().is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn corrupted_file_yields_empty_store() {
        let dir = temp_dir("corrupted");
        let path = dir.join("devices.json");
        fs::write(&path, "{ broken").expect("写入失败");
        let store = DeviceStore::load(path);
        assert!(store.list().is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn add_then_get() {
        let dir = temp_dir("add");
        let mut store = DeviceStore::load(dir.join("devices.json"));
        store.add(sample("device-0001", "Pixel"));
        assert_eq!(store.list().len(), 1);
        assert_eq!(
            store.get("device-0001").map(|d| d.name.as_str()),
            Some("Pixel")
        );
        assert!(store.get("device-9999").is_none());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn add_replaces_device_with_same_id() {
        let dir = temp_dir("replace");
        let mut store = DeviceStore::load(dir.join("devices.json"));
        store.add(sample("device-0001", "Old"));
        store.add(sample("device-0001", "New"));
        assert_eq!(store.list().len(), 1);
        assert_eq!(
            store.get("device-0001").map(|d| d.name.as_str()),
            Some("New")
        );
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn remove_device() {
        let dir = temp_dir("remove");
        let mut store = DeviceStore::load(dir.join("devices.json"));
        store.add(sample("device-0001", "Pixel"));
        assert!(store.remove("device-0001"));
        assert!(!store.remove("device-0001"), "重复删除应返回 false");
        assert!(store.list().is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn clear_removes_all() {
        let dir = temp_dir("clear");
        let mut store = DeviceStore::load(dir.join("devices.json"));
        store.add(sample("d1", "A"));
        store.add(sample("d2", "B"));
        store.clear();
        assert!(store.list().is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn persist_and_reload() {
        let dir = temp_dir("persist");
        let path = dir.join("nested").join("devices.json");
        let mut store = DeviceStore::load(path.clone());
        store.add(sample("device-0001", "Pixel"));
        store.save().expect("保存失败");

        let reloaded = DeviceStore::load(path);
        assert_eq!(reloaded.list(), store.list());
        fs::remove_dir_all(&dir).ok();
    }
}
