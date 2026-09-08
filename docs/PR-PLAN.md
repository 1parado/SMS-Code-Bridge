# PR 拆分计划（PRD → 可执行的小 PR）

把 PRD 拆成**可独立合并、可独立测试**的小 PR。每个 PR 只做一件事，带测试用例，合并后主线保持可用。

## 工作模式约定（强制）

1. **本地不编译、不打包**：本地只写代码与测试，不执行 `cargo build` / `gradlew assemble*` / 任何构建打包命令。
2. **编译与测试交给远程 CI**：push 或开 PR 时，GitHub Actions 自动跑格式化检查、静态检查、单元测试。CI 红 = 不合入。
3. **本地跑测试是可选项**：如果本机已装工具链，可自行 `cargo test` / `./gradlew testDebugUnitTest`；没装就完全依赖 CI，不强制搭建环境。
4. **只有里程碑版本才发布 Release**：日常 PR 只跑 CI，不出包。一个大版本的所有 PR 合并完 → 打 `vX.Y.Z` tag → 触发编译 + 体积门禁 + 发布两个产物。
5. **每个 PR 必须带测试**：纯逻辑做成纯函数（便于 JVM / Rust 单测），平台相关（剪贴板、广播、托盘）用接口抽象 + 假实现注入。

## 里程碑总览

| 版本 | 目标 | 包含 PR | 产物 |
|---|---|---|---|
| **v0.1.0** | 核心闭环：配对 → 收到验证码 → 电脑剪贴板 | PR-00 ~ PR-10 | exe + apk |
| **v0.2.0** | 历史记录、设置开关、断连重连与解绑 | PR-11 ~ PR-14 | exe + apk |
| **v0.3.0** | 体积优化、低延迟打磨、健壮性与发布就绪 | PR-15 ~ PR-17 | exe + apk |

## PR 清单（按合并顺序）

| # | 标题 | 目录 | 依赖 | 规模 |
|---|---|---|---|---|
| PR-00 | CI 与发布流水线骨架 | `.github/` | — | S |
| PR-01 | 文档基线：README 与目录说明 | 根目录 | — | XS |
| PR-02 | Windows 端工程骨架 + 配置模块 | `computer/` | PR-00 | S |
| PR-03 | Android 端工程骨架 + 最小权限声明 | `phone/` | PR-00 | S |
| PR-04 | 共享协议定义与跨端测试夹具 | `shared/` + 两端 | PR-02/03 | M |
| PR-05 | 局域网发现与配对服务端 | `computer/` | PR-04 | M |
| PR-06 | 配对客户端与配对码输入 | `phone/` | PR-04 | M |
| PR-07 | 短信接收与验证码提取 | `phone/` | PR-03 | M |
| PR-08 | 接收服务：解包、校验、去重 | `computer/` | PR-05 | M |
| PR-09 | 剪贴板写入与托盘提示 | `computer/` | PR-08 | M |
| PR-10 | v0.1 端到端验收清单与修复窗口 | 两端 | PR-09 | S |
| PR-11 | 电脑端本地历史记录 | `computer/` | v0.1 | S |
| PR-12 | 手机端历史列表与清空 | `phone/` | v0.1 | S |
| PR-13 | 两端设置开关与持久化 | 两端 | PR-11 | M |
| PR-14 | 断连检测、重连与一键解绑 | 两端 | PR-13 | M |
| PR-15 | 体积优化与 20MB 门禁 | 两端 + CI | v0.2 | M |
| PR-16 | 低延迟与后台稳定性打磨 | 两端 | v0.2 | M |
| PR-17 | 错误提示、关于页与版本号一致性 | 两端 | PR-16 | S |

---

# v0.1.0 — 核心闭环

## PR-00 · CI 与发布流水线骨架
- **范围**
  - `.github/workflows/ci.yml`：`pull_request` / `push(main)` 触发，只做检查与测试，**不出包**
    - computer：`cargo fmt --check`、`cargo clippy -D warnings`、`cargo test`
    - phone：`testDebugUnitTest`（纯 JVM 单测）、`lintDebug`
    - 两端工程未创建时对应 job 自动跳过
  - `.github/workflows/release.yml`：仅 `push tags: v*.*.*` 触发
    - 编译 Windows exe + Android APK → 体积门禁（各 ≤ 20MB）→ 发布 GitHub Release
  - `.github/pull_request_template.md`：提醒「本次改动是否含敏感信息 / 是否补了测试」
- **不做**：Android 正式签名（v0.1 用调试签名，正式 keystore 后续走 GitHub Secrets，不入库）
- **验收**：开一个空白 PR，CI 全绿；打 `v0.0.0-test` tag 能生成 Release（验证后删除该 tag）
- **测试用例**：无业务代码。YAML 可用本地 `actionlint` 校验（可选，不进 CI）

## PR-01 · 文档基线
- **范围**：`README.md` 补齐（项目简介、目录结构、开发流程、本地不编译说明、CI/发布策略）
- **验收**：新人照 README 能知道代码放哪、怎么提 PR、什么时候发版
- **测试**：无代码

## PR-02 · Windows 端工程骨架 + 配置模块（`computer/`）
- **范围**：最小 `Cargo.toml`（依赖只留必需）、`src/main.rs`、`config.rs`（加载/保存/默认值）、日志初始化
- **不做**：网络、UI、托盘
- **验收**：`cargo test` 全绿；配置项有明确默认值，配置文件缺失时程序用默认值启动
- **测试用例**（`config.rs` 内联 `#[cfg(test)]`）
  - `default_config_has_expected_values`：默认值符合约定（自动复制开、提示开、端口默认值）
  - `load_missing_file_returns_default`
  - `save_then_load_roundtrip`
  - `malformed_json_falls_back_to_default`
  - `partial_config_missing_fields_use_defaults`

## PR-03 · Android 端工程骨架 + 最小权限声明（`phone/`）
- **范围**：Gradle + Kotlin 工程、包名、`AndroidManifest.xml` 只声明 5 个权限：`RECEIVE_SMS` `READ_SMS` `INTERNET` `ACCESS_WIFI_STATE` `ACCESS_NETWORK_STATE`
- **不做**：任何 UI 业务逻辑、任何额外权限、通知监听
- **验收**：权限清单与 Agent.md 完全一致，不多一项
- **测试用例**
  - `manifest_declares_only_allowed_permissions`：解析 manifest 断言权限集合 == 允许集合
  - `no_dangerous_permissions_declared`：断言不含存储/定位/通讯录/相机/录音

## PR-04 · 共享协议定义与跨端测试夹具（`shared/` + 两端）
- **范围**：定义配对请求/响应、验证码消息、版本号与消息类型；JSON 夹具放 `shared/testdata/`，两端共用同一批夹具做一致性测试
  - 消息体只含：类型、版本、验证码、时间戳（脱敏，**绝不含短信原文、手机号、发件人**）
- **不做**：传输层实现
- **验收**：两端对同一份夹具序列化/反序列化结果一致
- **测试用例**
  - Rust：`pair_request_roundtrip` / `code_message_roundtrip` / `unknown_field_is_ignored` / `version_mismatch_rejected`
  - Kotlin：`pairRequestRoundtrip` / `codeMessageRoundtrip` / `unknownFieldIgnored` / `versionMismatchRejected`
  - 夹具格式在两端测试中共享（`shared/testdata/*.json`）

## PR-05 · 局域网发现与配对服务端（`computer/`）
- **范围**：UDP 广播/mDNS 发现、6 位配对码生成、临时密钥派生、配对有效期、已配对设备持久化
- **不做**：公网、账号、云端
- **验收**：手机能在同一局域网发现电脑并完成配对；配对码一次有效、超时失效
- **测试用例**
  - `pairing_code_is_six_digits`
  - `wrong_pairing_code_rejected`
  - `expired_pairing_code_rejected`
  - `pairing_code_cannot_be_reused`
  - `key_derivation_is_deterministic_and_sufficient_length`
  - `paired_device_persisted_and_reloadable`

## PR-06 · 配对客户端与配对码输入（`phone/`）
- **范围**：发现设备、输入配对码（二维码可选）、配对状态机、本地保存配对凭据
- **验收**：配对成功/失败都有明确极简状态提示；失败可重试
- **测试用例**
  - `pairing_code_accepts_six_digits_only`
  - `state_machine_transitions`（未配对 → 配对中 → 已配对 / 失败）
  - `qr_payload_serialization_matches_spec`
  - `failed_pairing_clears_partial_state`

## PR-07 · 短信接收与验证码提取（`phone/`）★核心
- **范围**：`SmsReceiver` 广播接收 + **纯函数** `extract_code(sender, body)`（关键词命中 + 数字提取）
- **设计约束**：提取逻辑必须是不依赖 Android SDK 的纯函数，才能在 JVM 上单测
- **验收**：命中关键词短信能提取出验证码；非验证码短信不触发任何动作
- **测试用例**（夹具驱动，样本全部脱敏：发件人用 `10690000` 之类，手机号写 `138****0000`）
  - `extracts_code_from_chinese_keyword`（含「验证码」）
  - `extracts_code_from_english_keyword`（含 `code` / `Code` / `CODE`，大小写不敏感）
  - `handles_code_with_spaces_or_dashes`（`123 456` / `123-456`）
  - `handles_4_6_8_digit_codes`
  - `picks_code_when_body_has_multiple_numbers`
  - `ignores_sms_without_keyword`
  - `ignores_empty_or_blank_body`
  - `handles_very_long_body`
  - `sender_whitelist_disabled_by_default`（默认关闭时任意发件人均按关键词处理）
  - `sender_whitelist_enabled_filters_others`
  - `never_emits_full_sms_body`（断言输出中不含原文与手机号）

## PR-08 · 接收服务：解包、校验、去重（`computer/`）
- **范围**：监听端口、HMAC 校验、重放窗口、去重、非法来源丢弃
- **验收**：伪造/过期/重复消息一律丢弃；合法消息进入处理流程
- **测试用例**
  - `valid_hmac_accepted` / `invalid_hmac_rejected`
  - `replay_outside_window_rejected`
  - `duplicate_code_within_window_dropped`
  - `oversized_packet_rejected`
  - `unpaired_sender_dropped`
  - `message_contains_no_sms_body`（断言协议字段白名单）

## PR-09 · 剪贴板写入与托盘提示（`computer/`）
- **范围**：`Clipboard` trait + 真实实现 + 假实现（测试用）、托盘图标与菜单、Toast 开关
- **验收**：验证码到达后自动进入剪贴板；提示可关闭；托盘常驻
- **测试用例**
  - `writes_code_to_clipboard`（假实现断言写入内容）
  - `auto_copy_disabled_does_not_write`
  - `toast_disabled_does_not_notify`
  - `tray_menu_state_matches_config`
  - `empty_or_invalid_code_is_not_written`

## PR-10 · v0.1 端到端验收清单与修复窗口
- **范围**：`docs/acceptance-v0.1.md` 手动验收清单 + 修复本轮暴露的问题
- **手动验收项**（无法自动化，必须真机 + 局域网）
  - 同一 WiFi 下配对成功
  - 手机收到验证码 → 电脑剪贴板自动出现，全程无需手动操作
  - 全链路延迟主观无感（目标毫秒 ~ 极低百毫秒）
  - 关闭提示后完全静默
  - 断开配对后手机推送不再被接受
  - 不影响手机正常收短信与其他 App
- **验收**：清单全部勾选 → 打 `v0.1.0` tag → **触发远程 CI 编译并发布 Release**

---

# v0.2.0 — 历史、设置、连接管理

## PR-11 · 电脑端本地历史记录（`computer/`）
- **范围**：只存「时间 + 验证码」，容量上限（默认 50 条）、一键清空、本地落盘
- **验收**：重启后历史仍在；清空后文件不再残留验证码
- **测试用例**：`append_and_read_back` / `capacity_trimmed_to_limit` / `clear_removes_all` / `persists_across_restart` / `corrupted_file_falls_back_to_empty` / `history_never_stores_sms_body`

## PR-12 · 手机端历史列表与清空（`phone/`）
- **范围**：极简列表（时间 + 验证码）、清空按钮
- **验收**：列表与存储一致；清空后 UI 立即为空
- **测试用例**：`view_model_loads_history` / `clear_empties_list_and_storage` / `list_respects_capacity` / `masked_display_only`（列表不展示短信原文）

## PR-13 · 两端设置开关与持久化
- **范围**：自动复制、提示（Toast/提示音）、（可选）开机自启默认关；开关状态本地持久化
- **验收**：开关改动立即生效并重启后保留
- **测试用例**：`defaults_as_specified` / `toggle_persists` / `disabled_flags_skip_corresponding_action`

## PR-14 · 断连检测、重连与一键解绑
- **范围**：心跳、断开检测、指数退避重连、一键解绑（清密钥 + 清配对）
- **验收**：断网后能自动恢复；解绑后旧设备消息被拒
- **测试用例**：`heartbeat_timeout_marks_disconnected` / `reconnect_backoff_sequence` / `unbind_clears_credentials` / `message_from_unbound_device_rejected`
- **验收后**打 `v0.2.0` tag → 发布 Release

---

# v0.3.0 — 体积、延迟、健壮性

## PR-15 · 体积优化与 20MB 门禁
- **范围**：Cargo release profile（`lto` / `strip` / `panic=abort` / `opt-level` 权衡）、Gradle R8/minify、资源裁剪；CI 增加体积门禁（> 20MB 直接失败）
- **验收**：两端产物均 ≤ 20MB，且 CI 有硬性把关
- **测试用例**：CI 脚本断言产物字节数；`cargo test` / 单测保持全绿（优化不得破坏行为）

## PR-16 · 低延迟与后台稳定性
- **范围**：UDP 优先 + 保活、前台服务保活、减少序列化与唤醒开销、延迟埋点（p50/p95）
- **验收**：全链路延迟稳定在目标区间；后台长时间不掉线
- **测试用例**：`payload_size_within_budget` / `latency_stats_p50_p95_calculation` / `keepalive_interval_respected`

## PR-17 · 错误提示、关于页与版本号一致性
- **范围**：网络异常/权限被拒/配对失败的极简提示；关于页展示版本；两端版本号一致
- **验收**：无权限时给出清晰引导且不崩溃；版本号跨端一致
- **测试用例**：`error_code_to_message_mapping` / `missing_permission_does_not_crash` / `version_consistent_across_ends`
- **验收后**打 `v0.3.0` tag → 发布 Release

---

# 分支与合并规范

- 分支命名：`feature/PR-07-sms-extract`、`fix/PR-08-replay-window`
- 一个 PR 只对应一个编号，禁止把多个 PR 的事塞进一个 PR
- PR 描述必须写明：**做了什么 / 测试用例有哪些 / 是否涉及敏感信息**
- CI 全绿才能合并；合并方式 squash
- 里程碑所有 PR 合并 + 手动验收通过 → 打 tag 发布

# 发布操作（唯一出包途径）

```bash
git tag v0.1.0
git push origin v0.1.0     # 触发 release.yml：编译 → 体积门禁 → 发布 Release
```

Release 只上传两个文件：Windows 端可执行文件、Android 端 APK，各 ≤ 20MB。

---

# 进度记录

## 已发布

| 版本 | 内容 | 产物体积 |
|---|---|---|
| v0.1.0 | 配对 → 验证码 → 电脑剪贴板的核心闭环 | exe 233KB / apk 35KB |
| v0.2.1 | 历史记录、设置开关、断连重连与解绑 | exe 264KB / apk 39KB |
| v0.3.0 | 局域网自动发现、版本号一致性与关于信息 | exe 267KB / apk 42KB |
| v0.4.0 | Windows 托盘常驻、Android 正式签名支持 | exe 325KB / apk 42KB |
| v0.4.1 | 修复托盘图标 1813（资源名 "tray-default"），图标改用 icon.png 多尺寸 ICO | exe 363KB / apk 42KB |

## 实际 PR 编号映射

计划编号在实现过程中有小幅调整，实际合并顺序如下：

| 实际编号 | 内容 | 对应计划 |
|---|---|---|
| PR-00 ~ PR-04 | CI 骨架、文档基线、两端工程骨架、共享协议 | 同计划 |
| PR-05 | 配对码与会话密钥派生、已配对设备持久化 | PR-05（发现部分后移） |
| PR-06 | 配对客户端（含跨端固定向量互验） | PR-06 |
| PR-07 | 短信接收与分发（提取逻辑随 PR-05 一并合入） | PR-07 |
| PR-08 | 接收侧解包、MAC 校验、重放与去重 | PR-08 |
| PR-09 | 剪贴板写入、提示开关、UDP 传输层 | PR-09（托盘后移） |
| PR-10 / PR-11 | 电脑端主循环 / 手机端装配（配对界面、CodeBus、UDP 发送） | 新增装配项 |
| PR-12 ~ PR-15 | 历史记录、设置开关、断连检测重连与解绑 | PR-11 ~ PR-14 |
| PR-16 / PR-17 | 局域网发现：协议扩展、广播地址计算、手机端自动寻找电脑 | 补充缺失项 |
| PR-18 | 版本号一致性与关于信息 | 拆分自 PR-17 |
| PR-19 ~ PR-22 | 托盘常驻（菜单/channel、主循环装配、rc 图标资源）、签名支持、版本号统一 0.4.0 | 顺延项 v0.4 |
| PR-23 | 托盘图标 1813 修复：资源名 "tray-default"、icon.png 多尺寸 ICO，版本 0.4.1 | 缺陷修复 |

## 剩余事项

- **端到端真机验收**：配对、延迟、静默、解绑、不影响正常短信（清单见 `docs/acceptance-v0.4.md`）
- **正式签名**：Android release 在 CI 未配置 keystore Secrets 时回退调试签名，需配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
