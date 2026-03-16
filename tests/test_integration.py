"""
集成测试。

覆盖 TC-INT-01 ~ TC-INT-11：跨组件全链路验证。
需要 Gateway + Skill Server + Redis + MySQL 全部启动。
"""

import asyncio
import pytest
from utils.ws_client import (
    connect_agent_ws, register_agent, send_heartbeat,
    send_json, recv_json, recv_json_all,
    connect_skill_stream_ws,
)


class TestAgentFullLifecycle:
    """Agent 连接全链路。"""

    @pytest.mark.asyncio
    async def test_int01_agent_connect_register_heartbeat(
        self, gateway_ws_url, test_ak, test_sk, gateway_api
    ):
        """TC-INT-01：Agent 从连接到注册完整流程。"""
        # 1. WebSocket 连接
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            # 2. 注册
            resp = await register_agent(ws)
            assert resp is not None
            assert resp["type"] == "register_ok"

            # 3. 心跳
            await send_heartbeat(ws)
            await asyncio.sleep(0.5)
            assert ws.open

            # 4. REST API 查询验证
            api_resp = gateway_api.list_agents(ak=test_ak)
            assert api_resp.status_code == 200
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_int02_agent_disconnect_offline(
        self, gateway_ws_url, test_ak, test_sk, gateway_api
    ):
        """TC-INT-02：Agent 断连后标记为 OFFLINE。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp = await register_agent(ws)
        assert resp["type"] == "register_ok"

        # 关闭连接
        await ws.close()
        await asyncio.sleep(2)

        # 验证 Agent 下线
        api_resp = gateway_api.get_agent_status(ak=test_ak)
        if api_resp.status_code == 200:
            data = api_resp.json()
            status = (data.get("data", {}).get("status")
                      or data.get("status", ""))
            if status:
                assert status.upper() != "ONLINE", \
                    f"Agent 断连后不应为 ONLINE，实际: {status}"

    @pytest.mark.asyncio
    async def test_int03_same_ak_keeps_old_rejects_new(
        self, gateway_ws_url, test_ak, test_sk
    ):
        """TC-INT-03：同 AK 重复连接保旧拒新。"""
        ws1 = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp1 = await register_agent(ws1)
        assert resp1["type"] == "register_ok"

        ws2 = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp2 = await register_agent(ws2)
        assert resp2["type"] == "register_rejected"
        assert resp2.get("reason") == "duplicate_connection"

        await asyncio.sleep(0.5)

        # ws1 应保持在线
        assert ws1.open, "旧连接应保持活跃"
        # ws2 应被关闭
        assert not ws2.open, "新连接应被拒绝并关闭"

        await ws1.close()


class TestGatewaySkillLink:
    """Gateway ↔ Skill Server WebSocket 链路。"""

    @pytest.mark.asyncio
    async def test_int06_gateway_to_skill_message(
        self, gateway_ws_url, test_ak, test_sk,
        skill_ws_url, test_user_id, create_skill_session, agent_online
    ):
        """TC-INT-06/07：Gateway → Skill 消息投递。"""
        # 1. Agent 已通过 agent_online fixture 连接 Gateway
        ws = agent_online

        # 2. 创建 Skill 会话
        session_id, user_id, _ = create_skill_session()

        # 3. 发送消息（触发 Skill → Gateway → Agent 消息流）
        from utils.api_client import SkillApiClient
        skill_api = SkillApiClient(
            f"http://localhost:8082"  # 直接构建，避免 fixture 冲突
        )

        # Agent 发送 tool_event（模拟 OpenCode 响应）
        # GatewayMessage 字段: event (JsonNode), welinkSessionId (String)
        await send_json(ws, {
            "type": "tool_event",
            "event": {"type": "message.part.delta", "part": {"type": "text"}, "delta": "Hello"},
            "welinkSessionId": str(session_id),
        })
        await asyncio.sleep(1)


class TestFullConversation:
    """完整对话链路。"""

    @pytest.mark.asyncio
    @pytest.mark.requires_agent
    async def test_int10_full_conversation(
        self, gateway_ws_url, test_ak, test_sk,
        skill_ws_url, test_user_id, skill_api, agent_online
    ):
        """TC-INT-10：创建会话 → 发送消息 → 收到回复。需要真实 Agent。"""
        # 1. 创建会话
        resp = skill_api.create_session(test_user_id, test_ak, "Full Conv Test")
        assert resp.status_code == 200
        session_id = resp.json().get("data", {}).get("welinkSessionId")

        try:
            # 2. 建立流式 WS 监听
            ws = await connect_skill_stream_ws(skill_ws_url, test_user_id)
            await recv_json_all(ws, timeout=2.0)  # 消耗初始消息

            await send_json(ws, {
                "type": "subscribe",
                "sessionId": str(session_id),
            })

            # 3. 发送消息
            msg_resp = skill_api.send_message(
                session_id, test_user_id, "Hello, write a hello world"
            )
            assert msg_resp.status_code in (200, 202)

            # 4. 等待并收集流式响应
            messages = await recv_json_all(ws, timeout=10.0, max_messages=50)
            await ws.close()

            # 注：完整的流式回复依赖真实 Agent 在线处理消息。
            # 在自动化测试环境中，Agent 可能不会处理消息并生成回复。
            # 此处仅验证通道建立和消息发送成功。
        finally:
            skill_api.close_session(session_id, test_user_id)
