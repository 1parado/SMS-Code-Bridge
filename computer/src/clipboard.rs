//! 剪贴板写入与提示。
//!
//! 平台能力用 trait 抽象，测试注入假实现；真实实现只在 Windows 上生效。

/// 剪贴板写入失败原因。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipboardError {
    /// 空内容不写入
    Empty,
    /// 非数字内容不写入（验证码只可能是数字）
    NotDigits,
    /// 系统剪贴板不可用
    Unavailable,
}

pub trait Clipboard {
    fn set_text(&mut self, text: &str) -> Result<(), ClipboardError>;
}

/// 系统剪贴板（仅 Windows）。
pub struct SystemClipboard;

impl Clipboard for SystemClipboard {
    fn set_text(&mut self, text: &str) -> Result<(), ClipboardError> {
        validate(text)?;
        clipboard_win::set_clipboard_string(text).map_err(|_| ClipboardError::Unavailable)
    }
}

/// 测试用假剪贴板：记录写入内容与次数。
#[derive(Debug, Default, Clone, PartialEq)]
pub struct FakeClipboard {
    pub content: Option<String>,
    pub calls: usize,
    pub fail: bool,
}

impl Clipboard for FakeClipboard {
    fn set_text(&mut self, text: &str) -> Result<(), ClipboardError> {
        validate(text)?;
        self.calls += 1;
        if self.fail {
            return Err(ClipboardError::Unavailable);
        }
        self.content = Some(text.to_string());
        Ok(())
    }
}

fn validate(text: &str) -> Result<(), ClipboardError> {
    if text.is_empty() {
        return Err(ClipboardError::Empty);
    }
    if !text.chars().all(|c| c.is_ascii_digit()) {
        return Err(ClipboardError::NotDigits);
    }
    Ok(())
}

pub trait Notifier {
    fn notify(&mut self, text: &str);
}

/// 默认提示：只在标准输出留痕，不弹窗（保持极简、无打扰）。
#[derive(Debug, Default)]
pub struct ConsoleNotifier;

impl Notifier for ConsoleNotifier {
    fn notify(&mut self, text: &str) {
        println!("收到验证码（{} 位），已尝试写入剪贴板", text.len());
    }
}

/// 测试用提示器：记录被通知的内容。
#[derive(Debug, Default, Clone, PartialEq)]
pub struct FakeNotifier {
    pub events: Vec<String>,
}

impl Notifier for FakeNotifier {
    fn notify(&mut self, text: &str) {
        self.events.push(text.to_string());
    }
}

/// 处理一条到达的验证码：按开关决定是否写入剪贴板与提示。
pub struct CodeSink<C: Clipboard, N: Notifier> {
    clipboard: C,
    notifier: N,
    auto_copy: bool,
    notify_enabled: bool,
}

impl<C: Clipboard, N: Notifier> CodeSink<C, N> {
    pub fn new(clipboard: C, notifier: N, auto_copy: bool, notify_enabled: bool) -> Self {
        Self {
            clipboard,
            notifier,
            auto_copy,
            notify_enabled,
        }
    }

    /// 返回是否成功写入剪贴板。
    pub fn handle(&mut self, code: &str) -> bool {
        if code.is_empty() || !code.chars().all(|c| c.is_ascii_digit()) {
            return false;
        }
        let mut copied = false;
        if self.auto_copy && self.clipboard.set_text(code).is_ok() {
            copied = true;
        }
        if self.notify_enabled {
            self.notifier.notify(code);
        }
        copied
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sink(auto_copy: bool, notify_enabled: bool) -> CodeSink<FakeClipboard, FakeNotifier> {
        CodeSink::new(
            FakeClipboard::default(),
            FakeNotifier::default(),
            auto_copy,
            notify_enabled,
        )
    }

    #[test]
    fn writes_code_to_clipboard() {
        let mut sink = sink(true, true);
        assert!(sink.handle("482913"));
        assert_eq!(sink.clipboard.content.as_deref(), Some("482913"));
        assert_eq!(sink.clipboard.calls, 1);
        assert_eq!(sink.notifier.events, vec!["482913".to_string()]);
    }

    #[test]
    fn auto_copy_disabled_does_not_write() {
        let mut sink = sink(false, true);
        assert!(!sink.handle("482913"));
        assert_eq!(sink.clipboard.calls, 0);
        assert_eq!(sink.notifier.events, vec!["482913".to_string()]);
    }

    #[test]
    fn notify_disabled_does_not_notify() {
        let mut sink = sink(true, false);
        assert!(sink.handle("482913"));
        assert!(sink.notifier.events.is_empty());
    }

    #[test]
    fn empty_code_is_not_written() {
        let mut sink = sink(true, true);
        assert!(!sink.handle(""));
        assert_eq!(sink.clipboard.calls, 0);
        assert!(sink.notifier.events.is_empty());
    }

    #[test]
    fn non_digit_code_is_not_written() {
        let mut sink = sink(true, true);
        assert!(!sink.handle("48a913"));
        assert!(!sink.handle("482913 "));
        assert_eq!(sink.clipboard.calls, 0);
    }

    #[test]
    fn clipboard_failure_is_reported_as_not_copied() {
        let mut sink = sink(true, false);
        sink.clipboard.fail = true;
        assert!(!sink.handle("482913"));
        assert_eq!(sink.clipboard.calls, 1);
    }

    #[test]
    fn fake_clipboard_rejects_invalid_input() {
        let mut clipboard = FakeClipboard::default();
        assert_eq!(clipboard.set_text(""), Err(ClipboardError::Empty));
        assert_eq!(clipboard.set_text("abc"), Err(ClipboardError::NotDigits));
        assert_eq!(clipboard.calls, 0);
    }
}
