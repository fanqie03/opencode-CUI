# Mock Server

这个目录提供一套基于 `FastAPI + sse-starlette` 的协议 mock 服务，用于联调以下上游协议：

- 标准协议 SSE
- 灵雀 / 白泽 Dify 协议
- AgentMaker SSE
- Uniknow 普通 HTTP
- Athena `HTTP + SSE` 两段式

## 特性

- 支持 `fixed` 和 `realistic` 两种模式
- 忽略鉴权 header
- 固定示例返回
- 当输入以 `debug` 开头时，改为回显用户输入
- 若请求里已带 `conversation_id` 或 `sessionId`，则复用；否则自动生成随机值

## 目录

- `mock_server/app.py`: 服务入口与全部协议实现
- `tests/test_mock_server.py`: 基本测试
- `start_mock_server.ps1`: PowerShell 启动脚本
- `start_mock_server.bat`: Windows 批处理启动脚本

## 安装

```bash
cd mock-server
python -m pip install -r requirements.txt
```

## 启动

PowerShell:

```powershell
./start_mock_server.ps1
```

cmd:

```bat
start_mock_server.bat
```

手动启动:

```bash
cd mock-server
python -m uvicorn mock_server.app:app --host 0.0.0.0 --port 18080
```

启动后访问：

- Swagger: `http://127.0.0.1:18080/docs`
- Health: `http://127.0.0.1:18080/healthz`

## 模式说明

- `fixed`: 不管输入内容是什么，都返回固定示例内容
- `realistic`: 更接近真实行为，包含更完整的事件顺序、分片、`ping` 和延迟
- `debug` 回显: 对应协议的文本输入字段如果以 `debug` 开头，回复内容改为原样回显

## 接口清单

| 协议 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 标准协议 | `POST` | `/mock/standard/stream` | SSE，返回标准协议 `data:` 块 |
| 灵雀/白泽 agent/chatflow | `POST` | `/api/v1/chat-messages` | SSE，`variant=agent|chatflow` |
| 白泽 agent/chatflow | `POST` | `/v1/chat-messages` | SSE，`variant=agent|chatflow` |
| 白泽 workflow | `POST` | `/v1/workflows/run` | SSE |
| AgentMaker | `POST` | `/mock/agentmaker/sse` | SSE |
| Uniknow | `POST` | `/mock/uniknow/chat` | 普通 HTTP JSON |
| Athena 创建任务 | `POST` | `/mock/athena/tasks` | 返回 long 型 SSE id |
| Athena 拉流 | `GET` | `/mock/athena/stream?id=...` | SSE，内容采用标准协议结构 |

公共查询参数：

- `mode=fixed|realistic`

额外查询参数：

- `/api/v1/chat-messages` 和 `/v1/chat-messages` 支持 `variant=agent|chatflow`

## 请求字段映射

服务会从这些字段中提取用户输入，用于 `debug` 回显：

- 标准协议: `content`
- Dify: `query`，或 `input.text`
- AgentMaker: `userInput`
- Uniknow: `input_text`
- Athena: `content`，也兼容 `query/userInput/input_text`

## curl 示例

### 1. 标准协议 fixed

```bash
curl -N -X POST "http://127.0.0.1:18080/mock/standard/stream?mode=fixed" \
  -H "Content-Type: application/json" \
  -d "{\"type\":\"text\",\"content\":\"你好\",\"sendUserAccount\":\"c1234\"}"
```

### 2. Dify agent realistic

```bash
curl -N -X POST "http://127.0.0.1:18080/v1/chat-messages?mode=realistic&variant=agent" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"你好\",\"conversation_id\":\"conv-001\",\"user\":\"c1234\"}"
```

### 3. Dify chatflow debug 回显

```bash
curl -N -X POST "http://127.0.0.1:18080/api/v1/chat-messages?mode=realistic&variant=chatflow" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"debug 请原样返回\",\"user\":\"c1234\"}"
```

### 4. Dify workflow

```bash
curl -N -X POST "http://127.0.0.1:18080/v1/workflows/run?mode=realistic" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"你好\",\"user\":\"c1234\"}"
```

### 5. AgentMaker

```bash
curl -N -X POST "http://127.0.0.1:18080/mock/agentmaker/sse?mode=realistic" \
  -H "Content-Type: application/json" \
  -d "{\"agentUuid\":\"agent-001\",\"userInput\":\"你好\",\"sessionId\":\"session-001\",\"w3Account\":\"u001\",\"needSave\":true}"
```

### 6. Uniknow

```bash
curl -X POST "http://127.0.0.1:18080/mock/uniknow/chat?mode=fixed" \
  -H "Content-Type: application/json" \
  -d "{\"input_text\":\"你好\",\"user_id\":\"c1234\",\"robot_uuid\":\"robot-001\"}"
```

### 7. Athena 两段式

先创建任务：

```bash
curl -X POST "http://127.0.0.1:18080/mock/athena/tasks?mode=realistic" \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"你好\"}"
```

假设返回：

```json
{"data":123456789012345}
```

再拉取 SSE：

```bash
curl -N "http://127.0.0.1:18080/mock/athena/stream?id=123456789012345"
```

## 测试

```bash
cd mock-server
pytest -q
```
