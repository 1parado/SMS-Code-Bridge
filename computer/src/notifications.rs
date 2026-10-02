//! 用户触达：release 版无控制台窗口，关键事件通过系统弹窗呈现。
//!
//! 弹窗在独立线程中调用，绝不阻塞主循环（UDP 收发与托盘轮询）。

use windows_sys::Win32::UI::WindowsAndMessaging::{
    MessageBoxW, MB_ICONINFORMATION, MB_OK, MB_SETFOREGROUND, MB_TOPMOST,
};

/// 弹出一条信息提示（非阻塞：在独立线程调用 MessageBox）。
pub fn show_message(title: &str, text: &str) {
    let title_wide = to_wide(title);
    let text_wide = to_wide(text);
    // 弹窗窗口由本线程创建并持有消息循环，线程随弹窗结束而结束
    std::thread::spawn(move || unsafe {
        MessageBoxW(
            0,
            text_wide.as_ptr(),
            title_wide.as_ptr(),
            MB_OK | MB_ICONINFORMATION | MB_TOPMOST | MB_SETFOREGROUND,
        );
    });
}

fn to_wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}
