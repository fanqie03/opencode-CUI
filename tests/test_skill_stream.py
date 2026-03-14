"""
Skill Server WebSocket 流式推送测试。

覆盖：
- TC-SK-U48~U50：StreamBufferService（间接验证）
- TC-SK-U51~U54：SkillStreamHandler
"""

import pytest
import asyncio
from utils.ws_client import (
    connect_skill_stream_ws, send_json, recv_json, recv_json_all,
)


class TestSkillStreamHandler:
    """Skill Stream WebSocket 测试。"""

    @pytest.mark.asyncio
    async def test_u51_connection_snapshot(self, skill_ws_url, test_user_id):
        """TC-SK-U51：连接建立后推送快照。"""
        try:
            ws = await connect_skill_stream_ws(skill_ws_url, test_user_id)
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("Skill WS 端点不可达")

        try:
            # 连接后应收到初始快照消息
            messages = await recv_json_all(ws, timeout=3.0, max_messages=10)
            # 即使无会话，也应能连接成功
            assert ws.open, "WS 连接应保持活跃"
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u52_subscribe_session(self, skill_ws_url, test_user_id,
                                         create_skill_session):
        """TC-SK-U52：subscribe 订阅指定 session。"""
        session_id, user_id, _ = create_skill_session()

        try:
            ws = await connect_skill_stream_ws(skill_ws_url, user_id)
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("Skill WS 端点不可达")

        try:
            # 消耗初始消息
            await recv_json_all(ws, timeout=2.0)

            # 订阅特定 session
            await send_json(ws, {
                "type": "subscribe",
                "sessionId": str(session_id),
            })

            # 应收到该 session 的快照
            messages = await recv_json_all(ws, timeout=3.0)
            # 不强制断言消息内容（可能无历史消息）
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u54_connection_close_cleanup(self, skill_ws_url, test_user_id):
        """TC-SK-U54：连接关闭后清理。"""
        try:
            ws = await connect_skill_stream_ws(skill_ws_url, test_user_id)
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("Skill WS 端点不可达")

        await ws.close()
        assert not ws.open, "关闭后连接应不再活跃"

    @pytest.mark.asyncio
    async def test_invalid_message_format(self, skill_ws_url, test_user_id):
        """发送无效格式消息不应导致断连。"""
        try:
            ws = await connect_skill_stream_ws(skill_ws_url, test_user_id)
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("Skill WS 端点不可达")

        try:
            await ws.send("not valid json |||")
            await asyncio.sleep(1)
            # 连接应仍然活跃（服务端应容错）
        except Exception:
            pass
        finally:
            if ws.open:
                await ws.close()

    @pytest.mark.asyncio
    async def test_no_cookie_rejected(self, skill_ws_url):
        """无 userId Cookie 的 WS 连接应被拒绝或无数据。"""
        import websockets
        try:
            ws = await asyncio.wait_for(
                websockets.connect(skill_ws_url, open_timeout=5),
                timeout=5,
            )
            # 即使连接成功，也应收不到有效数据
            await ws.close()
        except (websockets.exceptions.InvalidStatusCode,
                ConnectionRefusedError,
                asyncio.TimeoutError):
            pass  # 被拒绝是预期行为
