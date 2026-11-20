# Go 候选自测 · 2026-11-20

候选为 `github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go@v0.1.0-rc.1`。声明最低 Go 版本为 1.21，实际安装、构建与客户端运行的最低验证版本为 Go 1.21.13（Windows amd64）。本地 Go module proxy 产物位于 `dist/goproxy`。模块 ZIP 的 SHA-256 为 `054c1f8519be47303280435fc374d2041be36b8d41c3aad6e7a2d93b6fd900ed`。

| 场景 | 自测结果 |
| --- | --- |
| 从候选安装 | Windows amd64 / Go 1.21.13 下新建空白消费 module 和独立 Go 缓存，用 `go get` 从文件代理安装候选，构建两个示例。新 SDK 无 `replace`，公开依赖由本机 Go 代理缓存提供，`go mod verify` 通过。 |
| 单段手动提交 | 从安装产物运行英语样本，收到最终文本和 27 个词项，`order` 为 0.28–0.50 秒。关闭逐词时间的另一场实测返回最终文本、0 个时间戳词项。回放覆盖最后一帧仅提交一次、增强事件延后和结果未收齐。 |
| 双语和说话人 | 从安装产物运行双语样本，收到 2 个最终、2 个时间戳和 2 个有索引的分离事件。片段 0 和 1 分别对应 `speaker_0`、`speaker_1`，转录文件有两行。FFmpeg 实时管道经 stdin 推送同一音频也收到这两段。未指定预期段数时，收尾超时保留两行并报告尾段完整性未确认。 |
| 热词和大词库 | 对同一客户端连开两条企业会话，每条都使用 50 个普通热词、10,000 个长度为 50 个 Unicode 码点的库词和首帧上下文。两条都收到最终文本和 27 个词项。回放检查 Query、首帧和后续帧的实际字段。 |
| 错误与关闭 | 假 Key 返回 HTTP 401。空词和 10,001 个库词均返回 `input_error` 与 1008。回放另覆盖错误后 1000、意外断连、主动关闭、等待超时、握手阶段取消和鉴权 URL 脱敏。 |
| 事件回放 | `testdata/sdk-event.json` 解出 27 个词项，含空格，文字与原始字段逐项一致。回放覆盖索引 0、空值、无索引时间戳、两种增强事件顺序、连续说话人合并和未知事件。 |
| 同项目共存 | 实际旧企业 SDK 的源码版本为 `1af86ced890d0364ce5b3d06629837e081dc8757`。在同一消费 module 中引入旧包和新候选，先用旧包调用 `echo_v1_realtime`，获得非空最终原始事件及 29 个原始时间词项，再用新包调用 `echo_v2_realtime`。旧仓库基础示例的 `main.go` 逐字节未改，运行时用同一进程中的辅助初始化传递环境凭证，也读到旧模型的最终原始事件。旧版的普通最终类型化回调仍返回空 `Text`，该现象可用 `OnMessage` 的原始事件核对。 |

`go test ./... -count=1` 在 Go 1.21.13 通过。Go 1.26.1 下 `go vet ./...` 和 `go test ./... -shuffle=on -count=10` 通过。示例用法、外部消费 module 的安装命令、各事件与字段见 [README](README.md)。

## 目录索引

- SDK 源码与测试在 `go/` 根目录，回放夹具在 `testdata/`。
- 短录音示例在 `examples/segment/`，持续通话示例在 `examples/stream/`。
- 候选的 `.zip`、`.mod` 和 `.info` 在 `dist/goproxy/github.com/!my!vocal-!a!i/enterprise-echo-v2-realtime-sdks/go/@v/`，用 `python scripts/build_candidate.py` 重新生成。

企业实测使用 `../samples/english.pcm` 和 `../samples/bilingual.pcm` 两段合成音频。验收中的凭证仅在进程内读取。真实网关的 `speakers_merged` 仍待观测，本轮覆盖固定报文回放。候选版本为本地安装产物，GitHub 发布与线上文档更新留待发布环节。
