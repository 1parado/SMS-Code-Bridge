//! 传输层：UDP 收发封装。
//!
//! 只做最小封装，便于在回环地址上单测；不含任何业务判断。

use std::io;
use std::net::{SocketAddr, UdpSocket};

/// 单帧最大字节数，与接收侧包体上限保持一致。
pub const MAX_FRAME_BYTES: usize = 4096;

pub struct UdpTransport {
    socket: UdpSocket,
}

impl UdpTransport {
    /// 绑定到本地端口（传 0 由系统分配）。
    pub fn bind(port: u16) -> io::Result<Self> {
        let socket = UdpSocket::bind(("0.0.0.0", port))?;
        Ok(Self { socket })
    }

    pub fn local_addr(&self) -> io::Result<SocketAddr> {
        self.socket.local_addr()
    }

    /// 接收一帧，返回 (字节数, 来源地址)。超长帧被截断到缓冲区长度。
    pub fn recv(&self, buffer: &mut [u8]) -> io::Result<(usize, SocketAddr)> {
        self.socket.recv_from(buffer)
    }

    pub fn send_to(&self, bytes: &[u8], target: SocketAddr) -> io::Result<usize> {
        if bytes.len() > MAX_FRAME_BYTES {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "帧过长"));
        }
        self.socket.send_to(bytes, target)
    }

    /// 设置读取超时，避免无消息时永久阻塞。
    pub fn set_read_timeout_millis(&self, millis: u64) -> io::Result<()> {
        self.socket
            .set_read_timeout(Some(std::time::Duration::from_millis(millis)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bind_to_ephemeral_port_returns_address() {
        let transport = UdpTransport::bind(0).expect("绑定失败");
        let addr = transport.local_addr().expect("获取地址失败");
        assert_ne!(addr.port(), 0);
    }

    #[test]
    fn send_and_receive_over_loopback() {
        let server = UdpTransport::bind(0).expect("绑定服务端失败");
        let client = UdpTransport::bind(0).expect("绑定客户端失败");
        // 服务端绑定在 0.0.0.0，必须向回环地址发送（向 0.0.0.0 发送在 Windows 上无效）
        let server_port = server.local_addr().expect("获取服务端地址失败").port();
        let server_addr: SocketAddr = format!("127.0.0.1:{server_port}")
            .parse()
            .expect("解析回环地址失败");
        server.set_read_timeout_millis(2_000).expect("设置超时失败");

        let payload = b"hello-bridge";
        let sent = client.send_to(payload, server_addr).expect("发送失败");
        assert_eq!(sent, payload.len());

        let mut buffer = [0u8; 64];
        let (size, from) = server.recv(&mut buffer).expect("接收失败");
        assert_eq!(&buffer[..size], payload);
        assert_eq!(
            from.port(),
            client.local_addr().expect("获取地址失败").port()
        );
    }

    #[test]
    fn oversized_frame_is_rejected() {
        let client = UdpTransport::bind(0).expect("绑定失败");
        let target: SocketAddr = "127.0.0.1:1".parse().expect("解析地址失败");
        let oversized = vec![0u8; MAX_FRAME_BYTES + 1];
        assert!(client.send_to(&oversized, target).is_err());
    }
}
