# Java 候选包自测记录

2026-11-20（UTC+8）。候选坐标 `ai.myvocal:echo-v2-realtime-asr:0.1.0-rc.1`，导入路径 `ai.myvocal.echo.realtime`。环境为 Windows、Eclipse Temurin 11.0.29+7 和 Maven 3.9.12。JAR 字节码主版本为 55。

| 验证入口 | 实际结果 |
| --- | --- |
| SDK 源码执行 `mvn clean package` | 11 项通过。覆盖原始 27 个词项、零值和空值、两个增强事件顺序、请求超时、握手状态和凭证脱敏、首帧配置、关闭及未知事件。回放报文与原验收报文 SHA-256 相同。 |
| 干净独立 Maven 工程和空本地仓库 | 使用候选二进制 JAR 和 POM 安装，自动解析传递依赖。应用以 `--release 11` 编译。示例测试 1 项通过，覆盖索引 0、重复索引、两个到达顺序、无索引的相同文本、链式合并及独立时间戳。 |
| 发布 ZIP | 解包后 JAR 和 POM 与独立工程安装物相同。使用解包示例命令的相对音频路径编译、读取文件并发起握手，故意使用无效 Key 时得到 HTTP 401。 |
| 企业网关 · `english.pcm` | 纯文本模式返回完整英文。时间戳模式返回 27 个词项，`order` 为 0.28–0.50 秒。手动模式同时开启时间戳和分离，最终和分离事件的索引均为 0，时间戳有 27 项，分离词项带说话人。 |
| 企业网关 · `bilingual.pcm` | 收到 100 个 partial、2 个 committed、2 个 diarized 和 2 个逐词时间事件。最终稿保留英文和中文各一段。索引 0/1 对应 `speaker_0`/`speaker_1`，两条时间戳消息均无索引。保存稿每段最终文本一次，尾段标记为 `SESSION_END_UNCONFIRMED`。关闭为 `LOCAL`、代码 1000。 |
| 企业网关 · 热词及失败 | 50 条普通热词、10,000 条库词（含一条 50 字）、首帧上下文同用。两条新会话均返回非空文本和 27 个词项。无效 Key 返回 HTTP 401。空词和超限由 SDK 捕获，错误事件与之后的关闭顺序使用固定报文重放。 |

企业接口探针只使用共享合成音频。测试 Key 通过现有授权渠道进程内注入。保存的持续会话输出可见 [call.txt](.verification/external-final/call.txt)。

说话人合并通过回放验证，网关的实际发送情况待确认。麦克风采集路径已编译，实体录音设备待验证。Gradle 已给出本地依赖配置，构建实测使用 Maven。旧版企业实时 ASR 的发布物为 Python 和 Go。此次可见的 MyVocal 仓库没有对应的旧企业 Java SDK，Java 双依赖共存项不适用。[MyVocal GitHub](https://github.com/MyVocal-AI/)

## 产物 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `echo-v2-realtime-asr-0.1.0-rc.1.jar` | `238d9c8da6cb23fdc1445250964c0276eba426f07480da568cab86c1845af016` |
| `echo-v2-realtime-asr-0.1.0-rc.1.pom` | `be145b6f646b8489bbc48947a3323fb40f62f7e43c15a2584e2bce99dcba963f` |
| `echo-v2-realtime-asr-0.1.0-rc.1-sources.jar` | `e07f98cda077f45c2c692ee4964ae45b706b7e0efc4df1ebd7f4e5944696f515` |
| `myvocal-enterprise-java-echo-v2-realtime-asr-0.1.0-rc.1.zip` | `d56baad32082c27960bcfea144846c5926e793486f4fadc7e3d3a3edc354f48a` |
