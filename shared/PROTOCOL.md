# SMS Code Bridge 局域网协议 v3

两端共用（`computer/src/protocol.rs` + `phone/.../protocol/Protocol.kt`），传输层为 UDP（数据端口 + 发现端口）。
版本不一致的消息一律拒绝；两端代码、`shared/testdata/` 夹具与本文档必须保持同步。

## 密码学方案（借鉴 wx-ime-sdk「凭据不上网」原则）

### 配对码配对（默认，适合任何网络）的派生链

```
配对码 code（6 位数字，电脑端显示、手机端输入，绝不上网）
        │
        ▼
proof = PBKDF2-HMAC-SHA256(code, salt, 100_000)   ← salt 为手机生成的 16 字节随机盐，随请求上网
        │
        ▼
secret = HMAC-SHA256(proof, "sms-code-bridge/session-v2")   ← 会话密钥，从不上网
        │
        ├─ session_id = hex(HMAC-SHA256(secret, "session-id")[0..8])   ← 可公开，双向验证用
        ├─ 验证码消息  = AES-256-GCM(key=secret, iv=12B 随机, aad="code|{ts}", 明文=code)
        └─ 解绑认证    = hex(HMAC-SHA256(secret, "unpair|{ts}"))
```

设计要点：

- **配对码不上网**：PairRequest 只携带盐值与证明。嗅探者拿到证明后，仍需对被
  PBKDF2 拉伸的 6 位配对码做离线爆破（约 10⁶ × 10⁵ 次 HMAC）；配合电脑端
  「单挑战 + 最多 5 次失败尝试 + 2 分钟过期」，在线爆破不可行。
- **证明与密钥分离**：上网的 proof 未经再派生不能直接当会话密钥用。
- **验证码内容加密**：AES-256-GCM，密文含 16 字节认证标签；时间戳作为 AAD
  绑定进标签，防止「密文 + 时间戳」拆开重放。GCM IV 每条消息随机，且作为
  重放检测的键。
- **PairResponse 防伪造**：session_id 是会话密钥的 HMAC 截断，手机端校验
  本地派生值一致才认为配对成功——没有密钥的伪造者算不出这个值。
- **Unpair 认证**：解绑必须携带 HMAC(secret, "unpair|{ts}") 且 ts 在重放窗口内。
- **密钥卫生**：两端密钥类型在 Debug / toString 中一律脱敏；密钥仅存本机，
  从不上网。

### 一键配对（v3 新增，仅建议可信网络）

```
手机端「一键配对」 ──pair_open_request{device_id}──▶ 电脑端
        （电脑端弹窗提醒用户；等待用户托盘点「同意配对」，60 秒窗口）
手机端 ◀──pair_grant{device_id, secret_hex, session_id}── 电脑端确认
        （secret 为电脑端生成的 32 字节随机会话密钥，单播明文下发）
```

- 便利与安全的权衡：免输配对码的代价是**凭据在局域网内明文单播一次**。
  在交换式家用网络中其他主机嗅探不到该单播；但在被镜像/嗅探的网络上，
  攻击者可截获凭据。因此**公共网络请使用配对码配对**。
- 缓解措施：应答仅单播给请求方；手机端只接受来自所请求电脑 IP 的 grant；
  电脑端必须由用户在托盘显式确认（60 秒窗口，一次一设备）。

### 已知边界（有意为之 / 记录在案）

- 心跳消息未认证：伪造心跳只影响在线状态展示，不影响验证码准入。
- PBKDF2 迭代次数固定为常量（100 000），不上网传输，避免降级攻击。
- 两端对 MAC / 密文使用非常量时间字符串比较：局域网 UDP 场景下时序侧信道
  实际不可利用，换取零依赖的简单实现。
- 会话密钥在本机为明文存储（与 wx-ime-sdk 存身份 PEM 同级），依赖系统文件权限。

## 消息一览（JSON，UTF-8）

所有消息 `v` 均为 `3`。单帧上限 4096 字节。

| 消息 | 方向 | 字段 |
|---|---|---|
| `pair_request` | 手机 → 电脑 | `device_id`、`salt_hex`(32 hex)、`proof_hex`(64 hex) |
| `pair_response` | 电脑 → 手机 | `ok`、`session_id`(成功时，16 hex) |
| `pair_open_request` | 手机 → 电脑 | `device_id` |
| `pair_grant` | 电脑 → 手机（单播） | `device_id`、`secret_hex`(64 hex)、`session_id` |
| `code` | 手机 → 电脑 | `ts`、`iv_hex`(24 hex)、`ct_hex`(密文‖标签，hex) |
| `heartbeat` | 手机 → 电脑 | `device_id` |
| `discovery_request` | 手机 → 电脑（广播） | `device_id` |
| `discovery_response` | 电脑 → 手机 | `device_id`、`name`、`port` |
| `unpair` | 手机 → 电脑 | `device_id`、`ts`、`mac_hex`(64 hex) |

示例（即 `shared/testdata/` 夹具）：

```json
{"type":"pair_request","v":3,"device_id":"device-0001","salt_hex":"30313233343536373839616263646566","proof_hex":"887fc045…"}
{"type":"pair_grant","v":3,"device_id":"device-0001","secret_hex":"000102…1f","session_id":"0f5ae74e37fae0e8"}
{"type":"code","v":3,"ts":1757337600000,"iv_hex":"000102030405060708090a0b","ct_hex":"733ae422…"}
```

## 测试夹具约定（`shared/testdata/`）

- 夹具中所有凭据均为公开的测试值；`code_message.json` / `unpair.json` /
  `pair_grant.json` 使用测试密钥 `000102…1f`（0x00-0x1f 顺序 32 字节）构造。
- `pair_request.json` 的 `proof_hex` = PBKDF2-HMAC-SHA256("123456",
  hexDecode("30313233343536373839616263646566"), 100_000)。
- 两端测试对同一夹具断言：解析字段、解密结果、seal 重加密结果逐字节一致
  （Rust：`receiver.rs` / `pairing.rs` / `protocol.rs` 内联测试；
  Kotlin：`CryptoTest` / `PairingClientTest` / `ProtocolTest`）。

## 版本历史

| 版本 | 变更 |
|---|---|
| 1 | 明文验证码 + HMAC 字段；配对码明文上网（已废弃） |
| 2 | 配对码不上网（PBKDF2 证明）；验证码 AES-256-GCM 加密；Unpair 认证；session_id 改为派生值；配对失败次数限制 |
| 3 | 一键配对（pair_open_request / pair_grant，电脑端用户确认后随机会话密钥单播下发）；应答来源校验 |
