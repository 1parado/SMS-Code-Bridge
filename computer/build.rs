fn main() {
    // 把 app.rc（托盘图标资源）打包进最终二进制
    #[cfg(windows)]
    embed_resource::compile("app.rc", embed_resource::NONE);
}
