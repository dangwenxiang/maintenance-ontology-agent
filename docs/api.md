# v0.2 API

默认地址：http://127.0.0.1:8087 。JSON请求使用 `Content-Type: application/json`。

| 方法 / 路径 | 作用 |
|---|---|
| GET /api/health | Java状态、DSH启用/连接状态、演示或模型模式、审核是否启用 |
| GET /api/devices | 设备档案 |
| GET /api/cases?model=TIGHTEN-DEMO-A | 已审核维修案例 |
| GET /api/documents | 资料列表，含有效/停用状态 |
| GET /api/ontology | 首版概念与关系定义 |
| POST /api/ask | 维修问答，返回依据和执行记录 |
| POST /api/runs | 提交DSH异步任务，返回任务及会话ID |
| GET /api/runs/{id}?cursor=N | 任务状态、指定游标之后的事件、完成后的问答结果 |
| POST /api/runs/{id}/cancel | 等待DSH任务停止 |
| GET /api/sessions | 已保存的本机演示会话 |
| GET /api/traces/{id} | 历史问答的原始证据快照 |
| POST /api/feedback | 提交反馈，返回待审核案例ID |
| GET /api/review | 待审核案例，需要X-Admin-Token |
| POST /api/review/{caseId} | 通过或驳回，需要X-Admin-Token |
| POST /api/documents | 发布文本资料，需要X-Admin-Token |

## 问答请求

```json
{"question":"同型号设备出现扭矩不合格，历史上确认过哪些原因？","deviceId":"D-DEMO-01"}
```

问题中的显式设备编号优先于页面选中的设备。未知编号不自动替换成其他设备。`mode`区分evidence、dsh_demo、dsh_model和dsh_evidence_fallback。无资料时问答status为insufficient_evidence。DSH任务status与业务问答status分别表示执行和依据情况。

POST /api/runs接受同样请求，附加sessionId可在同一设备的原会话继续。切换设备应新建会话。完成后response包含原问答字段及kernel、dshVersion、sessionId。使用GET /api/runs获取完成结果时会保存原始业务证据，便于随后提交反馈。

异步接口不自动处理执行失败；调用者需处理cancelled、failed和interrupted。POST /api/ask是同步兼容入口，等待DSH完成；直接启动Java而未启用DSH时仅返回确定性业务查询结果。

内部工具网关POST /internal/dsh/tools只允许DSH服务调用，需要独立的X-DSH-Tool-Token，不应从浏览器使用。

## 维修反馈

traceId使用问答响应的id。无法确定设备或现象的问答不能提交反馈。

```json
{"traceId":"Q-实际问答ID","cause":"现场确认的原因","action":"实际处理过程","result":"实际结果"}
```

## 审核

```json
{"approve":true,"reviewer":"审核人姓名"}
```

审核请求使用启动环境中的凭证作为X-Admin-Token。记录只能由pending转为approved或rejected，重复审核返回409。

## 文本资料发布

```json
{"title":"演示设备维护说明","version":"2.0","model":"TIGHTEN-DEMO-A","configVersion":"A-1","section":"3.2","content":"扭矩不合格的适用说明……"}
```

同标题、型号、配置的新版本发布会停用原版本。同一版本再次发布返回409。历史问答保留查询时的文本快照。

## 部署边界

首版为本机个人演示：只保护审核和资料发布接口，问答和查询接口没有用户认证。不要直接公开暴露；生产部署需补用户认证、数据授权及请求限制。当前没有上传解析接口，不能把文本发布称为完整文档入库。
