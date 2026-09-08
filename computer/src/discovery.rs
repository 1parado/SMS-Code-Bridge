//! 局域网发现：广播地址计算与候选地址筛选。
//!
//! 纯计算部分不依赖平台，可直接单测；实际收发由 transport 完成。

use std::net::Ipv4Addr;

/// 发现服务固定端口（与数据端口区分）。
pub const DISCOVERY_PORT: u16 = 45877;

/// 由本机 IP 与子网掩码计算广播地址。
pub fn broadcast_address(ip: Ipv4Addr, mask: Ipv4Addr) -> Ipv4Addr {
    Ipv4Addr::from(u32::from(ip) | !u32::from(mask))
}

/// 判断两个地址是否位于同一子网。
pub fn same_subnet(a: Ipv4Addr, b: Ipv4Addr, mask: Ipv4Addr) -> bool {
    let mask = u32::from(mask);
    (u32::from(a) & mask) == (u32::from(b) & mask)
}

fn is_link_local(addr: Ipv4Addr) -> bool {
    addr.octets()[0] == 169 && addr.octets()[1] == 254
}

/// 从候选地址中挑出一个可用的局域网地址（排除回环与未指定地址）。
pub fn pick_lan_address(candidates: &[Ipv4Addr]) -> Option<Ipv4Addr> {
    candidates.iter().copied().find(|addr| {
        !addr.is_loopback() && !addr.is_unspecified() && (addr.is_private() || is_link_local(*addr))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(text: &str) -> Ipv4Addr {
        text.parse().expect("解析地址失败")
    }

    #[test]
    fn computes_class_c_broadcast_address() {
        assert_eq!(
            broadcast_address(ip("192.168.1.100"), ip("255.255.255.0")),
            ip("192.168.1.255")
        );
    }

    #[test]
    fn computes_class_b_broadcast_address() {
        assert_eq!(
            broadcast_address(ip("10.0.5.7"), ip("255.255.0.0")),
            ip("10.0.255.255")
        );
    }

    #[test]
    fn broadcast_of_full_mask_is_address_itself() {
        assert_eq!(
            broadcast_address(ip("192.168.1.1"), ip("255.255.255.255")),
            ip("192.168.1.1")
        );
    }

    #[test]
    fn detects_same_subnet() {
        let mask = ip("255.255.255.0");
        assert!(same_subnet(ip("192.168.1.10"), ip("192.168.1.200"), mask));
        assert!(!same_subnet(ip("192.168.1.10"), ip("192.168.2.10"), mask));
    }

    #[test]
    fn picks_private_address_and_skips_loopback() {
        let candidates = vec![ip("127.0.0.1"), ip("192.168.1.23"), ip("10.0.0.5")];
        assert_eq!(pick_lan_address(&candidates), Some(ip("192.168.1.23")));
    }

    #[test]
    fn no_lan_address_returns_none() {
        let candidates = vec![ip("127.0.0.1"), ip("0.0.0.0"), ip("8.8.8.8")];
        assert_eq!(pick_lan_address(&candidates), None);
    }
}
