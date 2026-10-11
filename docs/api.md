# v0.1 API

默认地址：http://127.0.0.1:8087 。JSON请求使用 `Content-Type: application/json`。

| 方法 / 路径 | 作用 |
|---|---|
| GET /api/health | 服务状态、模型是否配置、审核是否启用 |
| GET /api/devices | 设备档案 |
| GET /api/cases?model=TIGHTEN-DEMO-A | 已审核维修案例 |
| GET /api/documents | 资料列表，含有效/停用状态 |
| GET /api/ontology | 首版概念与关系定义 |
| POST /api/ask | 维修问答，返回依据和执行记录 |
| GET /api/traces/{id} | 历史问答的原始证据快照 |
| POST /api/feedback | 提交反馈，返回待审核案例ID |
| GET /api/review | 待审核案例，需要X-Admin-Token |
| POST /api/review/{caseId} | 通过或驳回，需要X-Admin-Token |
| POST /api/documents | 发布文本资料，需要X-Admin-Token |

## 问答请求

```json
{"question":"同型号设备出现扭矩不合格，历史上确认过哪些原因？","deviceId":"D-DEMO-01"}
```

问题中的显式设备编号优先于页面选中的设备。未知编号不自动替换成其他设备。`mode`区分evidence、model_tools和evidence_fallback。无资料时status为insufficient_evidence。

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
