//! 本地历史记录：仅保存「时间 + 验证码」，可清空，落盘持久化。
//!
//! **安全约束**：历史条目只含时间戳与验证码数字，绝不保存短信原文、
//! 手机号、发件人等任何敏感内容。此约束由 `HistoryEntry` 的字段强保证，
//! 任何新增字段都必须先评估是否触碰敏感信息边界。

use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// 单条历史记录：接收时间（毫秒）+ 验证码。
///
/// 只有这两个字段，从结构上杜绝了短信原文等敏感内容入库。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub ts: i64,
    pub code: String,
}

/// 历史存储：内存中维护最近 `limit` 条，支持落盘与清空。
#[derive(Debug, Clone)]
pub struct HistoryStore {
    limit: usize,
    entries: Vec<HistoryEntry>,
}

impl HistoryStore {
    pub fn new(limit: usize) -> Self {
        Self {
            limit: limit.max(1),
            entries: Vec::new(),
        }
    }

    /// 默认历史文件路径：`<用户配置目录>/sms-code-bridge/history.json`
    pub fn history_path() -> Option<PathBuf> {
        dirs::config_dir().map(|dir| dir.join("sms-code-bridge").join("history.json"))
    }

    /// 追加一条验证码记录；超过上限时丢弃最旧条目，保留最近 `limit` 条。
    pub fn append(&mut self, code: &str, ts: i64) -> &HistoryEntry {
        self.entries.push(HistoryEntry {
            ts,
            code: code.to_string(),
        });
        if self.entries.len() > self.limit {
            let excess = self.entries.len() - self.limit;
            self.entries.drain(0..excess);
        }
        self.entries.last().expect("刚插入的条目必然存在")
    }

    pub fn entries(&self) -> &[HistoryEntry] {
        &self.entries
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// 清空内存中的记录（落盘需再调用 `save_to`，写入空数组即不留残留）。
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    /// 从指定路径加载；路径为空、文件缺失或内容非法时一律回退空历史。
    pub fn load_from(path: Option<&Path>, limit: usize) -> Self {
        let Some(path) = path else {
            return Self::new(limit);
        };
        let Ok(content) = fs::read_to_string(path) else {
            return Self::new(limit);
        };
        let Ok(mut entries): Result<Vec<HistoryEntry>, _> = serde_json::from_str(&content) else {
            return Self::new(limit);
        };
        if entries.len() > limit {
            let excess = entries.len() - limit;
            entries.drain(0..excess);
        }
        Self {
            limit: limit.max(1),
            entries,
        }
    }

    /// 保存到指定路径（自动创建父目录）。清空时保存空数组，确保磁盘不留验证码。
    pub fn save_to(&self, path: &Path) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let content =
            serde_json::to_string(&self.entries).map_err(|err| io::Error::other(err.to_string()))?;
        fs::write(path, content)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    fn temp_path(tag: &str) -> PathBuf {
        static COUNTER: AtomicU32 = AtomicU32::new(0);
        let seq = COUNTER.fetch_add(1, Ordering::SeqCst);
        std::env::temp_dir().join(format!(
            "sms-code-bridge-hist-{}-{}-{}",
            tag,
            std::process::id(),
            seq
        ))
    }

    #[test]
    fn append_and_read_back() {
        let mut store = HistoryStore::new(50);
        store.append("482913", 1_000);
        store.append("774321", 2_000);
        assert_eq!(store.len(), 2);
        assert_eq!(store.entries()[0].code, "482913");
        assert_eq!(store.entries()[1].code, "774321");
    }

    #[test]
    fn capacity_trimmed_to_limit() {
        let mut store = HistoryStore::new(3);
        for i in 0..10 {
            store.append(&format!("{i:06}"), i as i64 * 1_000);
        }
        assert_eq!(store.len(), 3);
        // 只保留最近 3 条：000007 / 000008 / 000009
        assert_eq!(store.entries()[0].code, "000007");
        assert_eq!(store.entries()[1].code, "000008");
        assert_eq!(store.entries()[2].code, "000009");
    }

    #[test]
    fn clear_removes_all() {
        let mut store = HistoryStore::new(50);
        store.append("482913", 1_000);
        store.append("774321", 2_000);
        store.clear();
        assert!(store.is_empty());
    }

    #[test]
    fn persists_across_restart() {
        let path = temp_path("persist");
        let mut store = HistoryStore::new(50);
        store.append("482913", 1_000);
        store.save_to(&path).expect("保存失败");
        // 模拟进程重启：从磁盘重新加载
        let reloaded = HistoryStore::load_from(Some(&path), 50);
        assert_eq!(reloaded.len(), 1);
        assert_eq!(reloaded.entries()[0].code, "482913");
        fs::remove_file(&path).ok();
    }

    #[test]
    fn clear_persists_empty() {
        let path = temp_path("clear");
        let mut store = HistoryStore::new(50);
        store.append("482913", 1_000);
        store.save_to(&path).unwrap();
        store.clear();
        store.save_to(&path).unwrap();
        let reloaded = HistoryStore::load_from(Some(&path), 50);
        assert!(reloaded.is_empty(), "清空后磁盘不应残留任何验证码");
        fs::remove_file(&path).ok();
    }

    #[test]
    fn corrupted_file_falls_back_to_empty() {
        let path = temp_path("corrupt");
        fs::write(&path, "not a json array{").expect("写入失败");
        let store = HistoryStore::load_from(Some(&path), 50);
        assert!(store.is_empty());
        fs::remove_file(&path).ok();
    }

    #[test]
    fn missing_file_falls_back_to_empty() {
        let path = temp_path("missing");
        let store = HistoryStore::load_from(Some(&path), 50);
        assert!(store.is_empty());
    }

    #[test]
    fn history_never_stores_sms_body() {
        let mut store = HistoryStore::new(50);
        store.append("482913", 1_000);
        let json = serde_json::to_string(store.entries()).unwrap();
        // 序列化结果只允许出现 ts / code 两个字段，绝不出现敏感内容
        assert!(!json.contains("body"));
        assert!(!json.contains("sender"));
        assert!(!json.contains("+86"));
        assert!(!json.contains("短信"));
        assert!(json.contains("\"ts\""));
        assert!(json.contains("\"code\""));
    }
}
