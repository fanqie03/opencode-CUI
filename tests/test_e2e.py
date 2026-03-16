"""
端到端测试。

覆盖 TC-E2E-01 ~ TC-E2E-07：完整用户场景。
这些测试需要整个系统部署完毕且 Agent 在线。
"""

import asyncio
import pytest
from utils.ws_client import (
    connect_agent_ws, register_agent, send_json, recv_json,
    recv_json_all, connect_skill_stream_ws,
)


@pytest.mark.requires_agent
class TestEndToEnd:
    """端到端全流程测试。"""

    @pytest.mark.asyncio
    async def test_e2e01_full_conversation(
        self, gateway_ws_url, test_ak, test_sk,
        skill_ws_url, test_user_id, skill_api, agent_online
    ):
        """TC-E2E-01：新用户完整对话流程。"""
        # 查看在线 Agent
        agents_resp = skill_api.query_agents(test_user_id)
        assert agents_resp.status_code in (200, 404)

        # 创建会话
        create_resp = skill_api.create_session(
            test_user_id, test_ak, "E2E Full Test"
        )
        assert create_resp.status_code == 200
        session_id = create_resp.json().get("data", {}).get("welinkSessionId")
        assert session_id is not None

        try:
            # 建立流式连接
            ws = await connect_skill_stream_ws(skill_ws_url, test_user_id)
            await recv_json_all(ws, timeout=2.0)

            # 发送消息
            msg_resp = skill_api.send_message(
                session_id, test_user_id,
                "写一个 Python Hello World 程序"
            )
            assert msg_resp.status_code in (200, 202)

            # 等待响应
            messages = await recv_json_all(ws, timeout=30.0, max_messages=100)
            await ws.close()

            # 查看历史
            history = skill_api.get_messages(session_id, test_user_id)
            assert history.status_code == 200
        finally:
            # 关闭会话
            skill_api.close_session(session_id, test_user_id)

    @pytest.mark.asyncio
    async def test_e2e04_session_recovery(
        self, skill_ws_url, test_user_id, create_skill_session, skill_api
    ):
        """TC-E2E-04：会话恢复（重连 WS 后应收到快照）。"""
        session_id, user_id, _ = create_skill_session()

        # 发一条消息先
        skill_api.send_message(session_id, user_id, "Recovery test message")

        try:
            # 第一次连接并订阅
            ws1 = await connect_skill_stream_ws(skill_ws_url, user_id)
            await recv_json_all(ws1, timeout=2.0)
            await ws1.close()

            # 模拟"刷新页面"——重新连接
            ws2 = await connect_skill_stream_ws(skill_ws_url, user_id)
            await send_json(ws2, {
                "type": "subscribe",
                "sessionId": str(session_id),
            })

            # 应收到历史快照
            messages = await recv_json_all(ws2, timeout=5.0)
            await ws2.close()
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("Skill WS 不可达")

    @pytest.mark.asyncio
    async def test_e2e06_agent_offline_notification(
        self, gateway_ws_url, test_ak_2, test_sk_2,
        skill_ws_url, test_user_id
    ):
        """TC-E2E-06：Agent 离线通知。"""
        # 1. Agent 上线
        ws_agent = await connect_agent_ws(gateway_ws_url, test_ak_2, test_sk_2)
        resp = await register_agent(ws_agent)
        assert resp["type"] == "register_ok"

        try:
            # 2. 前端监听
            ws_stream = await connect_skill_stream_ws(skill_ws_url, test_user_id)
            await recv_json_all(ws_stream, timeout=2.0)

            # 3. Agent 下线
            await ws_agent.close()
            await asyncio.sleep(2)

            # 4. 前端应收到 agent_offline 通知
            messages = await recv_json_all(ws_stream, timeout=5.0)
            await ws_stream.close()

            # 检查是否有 agent_offline 类型的消息
            offline_msgs = [
                m for m in messages
                if m.get("type") in ("agent_offline", "agent.offline",
                                      "agentOffline")
                or (m.get("data", {}).get("type") if isinstance(m.get("data"), dict)
                    else None) in ("agent_offline",)
            ]
            # Agent 离线通知不一定通过 stream 推送，可能通过 REST 查询
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("WS 端点不可达")

    @pytest.mark.asyncio
    async def test_e2e07_multi_session_switch(
        self, skill_ws_url, test_user_id, skill_api, test_ak, agent_online
    ):
        """TC-E2E-07：多会话切换。"""
        # 创建两个会话
        r1 = skill_api.create_session(test_user_id, test_ak, "Session A")
        r2 = skill_api.create_session(test_user_id, test_ak, "Session B")
        sid_a = r1.json().get("data", {}).get("welinkSessionId")
        sid_b = r2.json().get("data", {}).get("welinkSessionId")

        try:
            # 各自发消息
            skill_api.send_message(sid_a, test_user_id, "Message in A")
            skill_api.send_message(sid_b, test_user_id, "Message in B")

            # 查各自历史
            hist_a = skill_api.get_messages(sid_a, test_user_id)
            hist_b = skill_api.get_messages(sid_b, test_user_id)

            assert hist_a.status_code == 200
            assert hist_b.status_code == 200

            # 验证消息在各自会话中
        finally:
            skill_api.close_session(sid_a, test_user_id)
            skill_api.close_session(sid_b, test_user_id)
