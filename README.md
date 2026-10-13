# 设备维修知识与诊断智能体

参考 [BeModel](https://github.com/yueyezhufeng/bemodel-java) 的业务语义与证据追溯思路，使用官方 [DeepSeek Harness（DSH）](https://github.com/deepseek-ai/deepseek-harness) 执行智能体。

**当前：v0.2 DSH接入版。维修业务由Java管理，Agent循环由DSH执行。初始资料均为合成演示数据；还不是完整企业平台。**

## 一键启动本地开发环境

需要 JDK >=21、Node.js >=22.19、Maven 3.9、Python 3。Node本地验证版本为25.9.0，CI使用24。

```sh
python3 scripts/dev.py
```

打开 http://127.0.0.1:8087 。启动脚本按需要构建Java程序、安装Node依赖，生成内部服务凭证，启动两个本机服务。凭证仅通过进程环境传递，不打印或提交。Ctrl+C停止服务并保留数据。

端口冲突时可以选择其他端口：

```sh
python3 scripts/dev.py --port 8088 --runtime-port 8118
```

脚本不会自动读取 `.env`。按 [.env.example](.env.example) 在启动环境中配置变量。

## 默认演示与真实模型

默认使用**演示模型适配器**，明确显示“DSH · 演示模型”。它按演示脚本产生工具调用，官方DSH负责真实的Agent循环、工具调度、会话、取消及持久化。不能用演示结果宣称真实模型的决策质量。

接入真实模型时配置：

- `MODEL_BASE_URL`：兼容chat/completions的基础地址，例如内网服务的 `http://模型地址:端口/v1`。
- `MODEL_NAME`：服务中真实可用的模型名称。
- `MODEL_API_KEY`：服务需要鉴权时配置；不在仓库中保存真实值。

模型需要支持标准原生Function Calling。当前适配器仅支持文本及原生工具协议，特殊thinking状态或其他厂商扩展尚未适配。使用真实模型时，问题和查询所得资料会发送到所配置的模型地址；客户内网部署需要使用内网服务。

## 现在能做什么

- 新建设备绑定的DSH会话，继续提问、查看执行过程、取消任务。
- 重启运行服务后，在原会话中继续；未完成任务标记为中断，不自动重放。
- 查询当前设备或同型号的已审核维修案例，检索型号/配置适用的有效文本资料。
- 展示记录ID、文档版本和业务依据；模型引用不通过校验时显示业务查询结果。
- 提交实际维修反馈，人工审核通过后成为正式案例。
- 发布文本资料，新版本停用同标题、型号和配置的旧版本；原问答保留证据快照。

当前故障示例仅覆盖“扭矩不合格”及同义描述。业务关系为固定领域模型，尚未实现通用本体编辑、字段映射和OWL推理。

## 架构与目录

```text
维修工作台 → Java API → DSH Runtime Host → 官方 DSH Agent Loop
                         ↑                    ↓
                  设备 / 案例 / 知识 ← 三个只读业务工具
```

| 路径 | 职责 |
|---|---|
| src/main/java | Spring Boot业务数据、反馈审核、DSH客户端和内部工具网关 |
| src/main/resources/static | 工作台、会话选择、异步任务查询与取消 |
| runtime/src | 独立Node Host、模型适配器、业务工具装配 |
| runtime/package-lock.json | 固定官方DSH 0.2.0-rc.2、Cordis 4.0.4及依赖完整性 |
| runtime/test | 官方DSH循环、会话恢复、取消与模型协议测试 |
| scripts/dev.py | 本机整套启动与停止 |
| scripts/dsh_smoke.py | 隔离环境中验证Java和DSH完整链路 |
| data/ | Java H2业务数据，Git忽略 |
| runtime/data/ | DSH持久会话和宿主执行记录，Git忽略 |

本项目独立实现，未复制BeModel或MOVO源码，未修改DSH内核。参考系统架构与使用官方插件接口不等于已经拥有参考项目的全部功能。

## 审核与权限

配置 `ADMIN_TOKEN` 才能启用审核及资料发布。页面输入同一凭证，不保存到浏览器存储。首版为共享审核凭证，审核人姓名由人工填写。

DSH内部接口使用独立服务凭证，工具绑定当前设备和本机演示身份；模型没有审核、数据库写入或设备控制工具。

目前只有 `local-demo` 身份，尚未实现企业登录、SSO、组织、角色和完整数据授权。问答页面仍是本机个人演示用途，不应将其宣称为可公开使用的企业平台。

## 验证

```sh
mvn -s .mvn/settings.xml -gs .mvn/settings.xml verify
cd runtime
npm ci --ignore-scripts
npm test
cd ..
python3 scripts/dsh_smoke.py
```

2026-10-13本地通过12项Java行为测试、6项Node测试，以及Java+DSH整套HTTP烟测。验证包含官方DSH原生工具调度、继续会话、重启恢复、取消、反馈审核和设备范围检查。

真实模型效果、客户资料、生产容量、Docker镜像构建尚未验证。本次浏览器工具访问被安全策略拦截，v0.2页面交互和截图检查未完成；保留v0.1历史截图不作为本版UI验收证据。CI结果以GitHub Actions为准。

单独构建的Java Dockerfile目前只提供业务服务，不包含整套DSH运行服务。完整容器化交付是后续工作。

## 文档与后续

- [DSH接入设计与验收](docs/dsh-integration.md)
- [运行服务说明](runtime/README.md)
- [API接口](docs/api.md)
- [验证记录](docs/validation.md)
- [原业务设计](docs/design.md)

接下来建设企业身份、工具审批与审计、文档解析/OCR、向量检索与重排序，再对接客户已有NATS和飞书平台。通用Skill管理、子智能体、后台调度、运维监控、备份升级等能力尚未交付。

DSH采用 [MIT许可证](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.0-rc.2/LICENSE)，发布时保留依赖中的版权与许可证并核对各依赖。DSH目前属于候选/预览发布，升级前在数据副本上验证会话恢复、取消、工具调度和回滚。
