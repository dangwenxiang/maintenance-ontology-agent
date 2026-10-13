# DSH Runtime Host

本服务独立实现，运行内核使用官方DSH包，未复制MOVO源码，未修改DSH内核。

当前固定DSH `0.2.0-rc.2`、Cordis `4.0.4`；完整依赖和完整性校验记录在package-lock.json。该版本是候选发布，不能当作稳定版保证。

## 分工

- 官方DSH负责Agent循环、原生工具调度、会话、取消、JSONL会话持久化。
- engine.mjs负责装配官方插件、业务会话绑定、任务状态、事件展示、工具范围与引用检查。
- models.mjs为模型协议适配器。默认DemoAdapter为明确标注的演示模型替身；CompatibleAdapter适配原生Function Calling接口。
- server.mjs提供内部HTTP接口，默认仅监听本机，所有接口需要内部服务凭证。
- Java工具服务提供设备、已审核案例和有效资料；DSH不直接操作业务数据库。

官方参考：[项目](https://github.com/deepseek-ai/deepseek-harness)、[插件装配测试示例](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.0-rc.2/packages/core/agent-loop/tests/loop.spec.ts)、[模型适配接口](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.0-rc.2/docs/cookbook/adding-an-llm-adapter.md)。

## 接口

内部HTTP路径：/v1/health、/v1/sessions、POST /v1/runs、GET /v1/runs/{id}?cursor=N、POST /v1/runs/{id}/cancel。

组织身份尚未建设：目前仅local-demo身份。会话绑定一个设备，不允许从提问文本改变工具查询范围。模型只获得三个只读工具；反馈审核仍在Java业务流程中。

任务状态：running、cancelling、completed、cancelled、failed、interrupted。重启会将未结束任务标为interrupted，保留DSH历史；需要重新提交问题继续原会话，不自动重放可能产生副作用的任务。

## 模型和限制

配置MODEL_BASE_URL、MODEL_NAME，按需要配置MODEL_API_KEY，即可使用兼容chat/completions及原生tool_calls的服务。URL是基础地址，例如http://内网服务:端口/v1。模型请求目前使用非流式HTTP响应，工作台流式展示的是执行事件，不是逐Token推送。

首版仅文本与只读工具，不装配shell、浏览器、文件写入、插件安装或子智能体。演示模型没有真实Token用量，不用其数据作成本或性能评估。引用ID校验不代表模型每个结论都得到事实证明。

上下文自动压缩、企业身份、跨进程水平扩展、通用Skill管理与审批平台是后续工作。每个数据目录只能由一个运行服务使用；升级DSH前应在数据副本上验证恢复和回滚。

## 检查

```sh
npm ci --ignore-scripts
npm test
```

从项目根运行python3 scripts/dev.py启动整套应用，或者python3 scripts/dsh_smoke.py进行隔离烟测。内部凭证由启动脚本生成，仅传给两个进程，不显示到页面或控制台。

DSH许可证：[MIT](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.0-rc.2/LICENSE)。发布时保留依赖中版权与许可声明，核对各依赖自己的许可证。
