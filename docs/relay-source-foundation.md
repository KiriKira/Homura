# 中继来源与 Worker 控制传输实现

本批实现 T1–T4 及 T6 手机侧协商。默认只使用节点准入时，URL 的顺序、分组、重复项与可选凭据保持原样。

## 来源与排序

设置入口“中继来源”支持静态 TURN、已配对节点和 HTTPS 来源链接/二维码；来源可以关闭、单独置顶或移除。
`mahogsm://relay?url=...&token=...` 明确确认后才保存/访问；HTTPS、参数唯一性、端口和 token 边界严格检查。
节点声明 `ice_servers_v1` 时复用其原会话，不另发 Cloudflare API token。来源列表用 Android Keystore AES/GCM 加密，既有备份规则排除全部私有配置。
存储损坏要求显式清除重建；通话仍可使用节点准入。账户、会话身份、配置代际隔离凭据缓存；401/403 清除来源缓存，不以旧凭据回退。

通话准备并行获取来源，共用 1.5 秒截止时间；同一身份/来源的并发刷新合并，剩余 30 分钟触发刷新、至少留 30 秒安全余量。
STUN RTT 按网络缓存 5 分钟，仅作为初始顺序提示，失败保留 TCP/TLS 等回退；用户置顶优先。
Madoka 振铃只预取来源，不申请媒体、不创建 PeerConnection、不启动 30 秒媒体协商期限。

只有 URL、用户名和密码相同才合并。重复 URL 的不同凭据在 native 创建前做真实 TURN challenge/认证 Allocate 的有界选择，
成功后尽力以 lifetime=0 释放临时 allocation；不同地址并行，共用 1.2 秒上限。无验证成功时保留首选供普通 ICE 尝试。
这避免依赖固定 SDK 对同一地址多组凭据的去重行为；配置中的备用凭据不会被删除。来源准备和该鉴权检查各有独立上限。

最多 8 来源（含当前节点）、16 组 server、32 个 URL；URL 数不是候选数。
总量超预算时先为当前节点预留容量，再按置顶/RTT 顺序保留能容纳的完整附加来源，记录 `relay_sources_truncated`；不会因此让媒体构造失败。节点本身已超预算时保留原列表，跳过所有附加来源，维持旧路径兼容。
其他附加配置校验异常在媒体边界回退节点列表，记录 `relay_sources_fallback`；设置输入仍严格校验。
多来源模式按类型限制实际 trickle：relay 64、host 32、reflexive 32，给 IPv4/IPv6/多网卡直连留余量；节点单来源保持原行为。
自建地址的自定义端口、TLS 5349/53 不被 Cloudflare 的端口规则误过滤。来源 ID 映射保留到选中候选统计；日志与通话界面显示来源或直连，未知归属明确显示待确认。

## 可选控制连接

仅在节点声明 `rtc_control_ws_v1` 后使用独立控制 WebSocket，HTTP 与旧 `/v1/ws` 仍保留。
共享连接复用 RTC、短信及网关心跳；已有控制连接可承担同步提示，避免另一条 wake 连接因 token 刷新重复握手。
控制消息带 request_id，副作用继续使用原 operation_id/Idempotency-Key；超时和连接故障用原载荷回退 HTTP。
连接内 refresh 先更新连接身份，再交给会话存储；过期/撤销、429 Retry-After、能力回滚及断连使缓存或传输能力失效。

空呼叫/命令/事件可缓存至通知或断连；活动呼叫仍至少每 5 秒真实续租，收到服务器快照也不能延后此截止时间。
握手连接数限流只退避握手，不阻断 HTTP 续租；设备日限额为结束及必要 CAS 读取保留有界预算。
冷备节点仅在主节点健康时暂停非活动探测，失败的同轮重新探测并激活；无推送仍可恢复。
网关真实心跳约 60 秒一次，持久 ACK 后推进 sequence。两端快速断连不清零退避计数，稳定 60 秒后才重置。网络恢复和身份刷新成功可提前普通重试计时（Madoka 网络稳定约 2 秒后、Homura 立即尝试），但不能提前服务器 Retry-After 的硬截止时间。

这一限流策略也适用于 SoulGem：节点业务 API 返回 429 后，手机暂停该节点带会话的普通请求至 Retry-After（缺省 60 秒）；结束及必要 CAS 读取保留例外，并非仅 GriefSeed 的行为。

## 验证与设备步骤

自动化：来源合并、鉴权回退、STUN/TLS 边界、链接、候选预算、控制连接刷新、缓存/退避与 HTTP 回退；应用测试、APK 构建和 lint。
Madoka 另覆盖加密存储、账户/401 失效、7 来源共同截止时间，以及无推送时冷备在同轮激活。

普通调试构建：`./gradlew :app:assembleDebug`。
强制中继模拟器构建：`./gradlew -PrtcEmulator=true -PforceRtcRelay=true :app:assembleDebug`（arm64/x86_64）。
强制中继开关仅影响 debug，release 始终保留默认直连策略。

仍需 KVM 模拟器/真机：固定 SDK 的真实选路与来源归属、坏/慢 TURN、UDP 不通但 TLS 可用、当前节点 TURN 关闭、同址凭据媒体回退、
长振铃接听/预取取消、双主机与蜂窝 END 竞态、Doze/网络切换/重连风暴。当前环境无 `/dev/kvm`，未把 JVM 测试当成媒体通话验收。
来源准备耗时以日志 `event=relay_sources_resolved elapsed_ms=... sources=...` 为准：它覆盖获取凭据和 STUN 测速，`native_ready`/`offer_sent` 的计时不包含这一段。
设备验收还需记录节点单来源及多来源的外呼/接听媒体就绪耗时：来源刷新最多 1.5 秒，同址凭据认证最多另加 1.2 秒，单节点 STUN 测速最多 250ms（按网络缓存 5 分钟）；Homura 两个方向均无预取，Madoka 仅来电振铃预取。
真实 Cloudflare 的 Deploy、key 权限、已有 allocation 撤销效果与 24 小时用量还需部署者账号验证；本批没有发布生产 Worker。
