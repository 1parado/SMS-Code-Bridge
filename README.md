# SMS Code Bridge

手机收到验证码 → 同一局域网 → 电脑剪贴板自动出现。投简历再也不用反复看手机。

- **本地优先**：数据只在本机与局域网流转，不经过任何公网服务器
- **极简轻量**：托盘常驻，平时几乎无存在感，两端产物各 ≤ 20MB
- **最小权限**：只申请真正需要的权限，只传输验证码本身，不传短信原文

## 目录结构

```
computer/   Windows 端（Rust）
phone/      Android 端（Kotlin）
shared/     两端共用的协议定义与测试夹具（纯文本）
docs/       开发文档与验收清单
```

仓库根目录只放文档与 CI 配置，源码一律按端分目录，详见 `Agent.md`。

## 开发流程

1. 需求已按 `docs/PR-PLAN.md` 拆成小 PR，**一个 PR 只做一件事**
2. 分支命名：`feature/PR-07-sms-extract`
3. 每个 PR 必须带单元测试；业务逻辑写成纯函数，平台能力用接口抽象 + 假实现
4. **本地不编译、不打包**：构建与测试全部交给 GitHub Actions
5. 日常 PR 只跑检查与测试，不出包；CI 全绿才能合并

```bash
# 日常：只跑检查与测试（在 CI 上执行，不在本地）
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test --all
./gradlew testDebugUnitTest
```

## 发版（唯一出包途径）

一个里程碑的所有 PR 合并 + 手动验收通过后：

```bash
git tag v0.1.0
git push origin v0.1.0
```

触发远程 CI：编译 Windows 端与 Android 端 → 体积门禁（各 ≤ 20MB）→ 发布 GitHub Release，
只上传两个文件：Windows 可执行文件 + Android APK。

## 里程碑

| 版本 | 目标 |
|---|---|
| v0.1.0 | 核心闭环：配对 → 收到验证码 → 电脑剪贴板 |
| v0.2.0 | 历史记录、设置开关、断连重连与解绑 |
| v0.3.0 | 体积优化、低延迟打磨、健壮性与发布就绪 |

详细拆分见 `docs/PR-PLAN.md`，产品需求见 `PRD.md`，开发强制规范见 `Agent.md`。
