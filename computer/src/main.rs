use sms_code_bridge::config::Config;

fn main() {
    let config = Config::load();
    println!("SMS Code Bridge {} (Windows)", env!("CARGO_PKG_VERSION"));
    match Config::config_path() {
        Some(path) => println!("配置文件: {}", path.display()),
        None => println!("配置文件: 不可用（无法定位用户配置目录）"),
    }
    println!("监听端口: {}", config.port);
    println!("自动复制: {}", if config.auto_copy { "开" } else { "关" });
}
