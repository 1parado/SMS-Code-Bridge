//! Windows 托盘常驻。
//!
//! 托盘窗口归属于独立线程（Windows 不限制线程），菜单动作通过 channel
//! 交给主循环处理；本模块不包含业务判断。

use tray_item::{IconSource, TrayItem};

/// 托盘菜单动作。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayAction {
    /// 显示当前配对码（打印到控制台）
    ShowPairingCode,
    /// 退出程序
    Quit,
}

/// 托盘菜单定义（label 与动作的映射）。
pub fn menu_items() -> Vec<(&'static str, TrayAction)> {
    vec![
        ("显示配对码", TrayAction::ShowPairingCode),
        ("退出", TrayAction::Quit),
    ]
}

/// 托盘事件接收端：主循环轮询 `try_recv` 即可。
pub struct TrayHandle {
    pub events: std::sync::mpsc::Receiver<TrayAction>,
}

/// 在独立线程创建托盘。创建失败只打日志，不影响主流程。
pub fn spawn() -> TrayHandle {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        // tray-item 0.10 的 Windows 实现硬编码加载名为 "tray-default" 的资源图标
        let tray_result = TrayItem::new("SMS Code Bridge", IconSource::Resource("tray-default"));
        let mut tray = match tray_result {
            Ok(tray) => tray,
            Err(error) => {
                eprintln!("托盘创建失败（程序继续以控制台方式运行）: {error}");
                return;
            }
        };

        for (label, action) in menu_items() {
            let sender = tx.clone();
            if let Err(error) = tray.add_menu_item(label, move || {
                let _ = sender.send(action);
            }) {
                eprintln!("托盘菜单项「{label}」创建失败: {error}");
            }
        }

        // 托盘窗口属于本线程，必须保持存活，否则图标会消失
        loop {
            std::thread::park();
        }
    });
    TrayHandle { events: rx }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn menu_contains_show_code_and_quit() {
        let items = menu_items();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].1, TrayAction::ShowPairingCode);
        assert_eq!(items[1].1, TrayAction::Quit);
    }

    #[test]
    fn menu_labels_are_unique() {
        let items = menu_items();
        let mut labels: Vec<_> = items.iter().map(|(label, _)| *label).collect();
        labels.sort();
        labels.dedup();
        assert_eq!(labels.len(), items.len(), "菜单项标签不应重复");
    }

    #[test]
    fn events_channel_delivers_actions() {
        let handle = TrayHandle {
            events: {
                let (tx, rx) = std::sync::mpsc::channel();
                tx.send(TrayAction::Quit).expect("发送失败");
                rx
            },
        };
        assert_eq!(
            handle.events.try_recv().expect("应能收到"),
            TrayAction::Quit
        );
        assert!(handle.events.try_recv().is_err(), "不应重复收到");
    }
}
