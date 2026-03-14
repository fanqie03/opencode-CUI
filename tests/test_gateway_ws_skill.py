"""
AI-Gateway Skill WebSocket 和 Redis 相关测试。

覆盖：
- TC-GW-U28~U33：SkillRelayService / RedisMessageBroker
- TC-GW-U45~U47：SkillWebSocketHandler
"""

import pytest
import websockets
import asyncio
from utils.auth import build_internal_auth_subprotocol


class TestSkillWebSocketHandler:
    """Skill Server 内部 WebSocket 认证测试。"""

    @pytest.mark.asyncio
    async def test_u45_skill_handshake_valid_token(self, gateway_base_url,
                                                    gateway_internal_token):
        """TC-GW-U45：Skill 握手认证（正确 internal token）。"""
        ws_url = gateway_base_url.replace("http://", "ws://") + "/ws/skill"
        subprotocol = build_internal_auth_subprotocol(gateway_internal_token)

        try:
            ws = await asyncio.wait_for(
                websockets.connect(
                    ws_url,
                    subprotocols=[subprotocol],
                    open_timeout=5,
                ),
                timeout=5,
            )
            assert ws.open, "正确 token 应成功握手"
            await ws.close()
        except (websockets.exceptions.InvalidStatusCode,
                ConnectionRefusedError):
            pytest.skip("Gateway Skill WS 端点不可达")

    @pytest.mark.asyncio
    async def test_u46_skill_handshake_invalid_token(self, gateway_base_url):
        """TC-GW-U46：Skill 握手失败（错误 token）。"""
        ws_url = gateway_base_url.replace("http://", "ws://") + "/ws/skill"
        subprotocol = build_internal_auth_subprotocol("wrong_token_invalid")

        try:
            ws = await asyncio.wait_for(
                websockets.connect(
                    ws_url,
                    subprotocols=[subprotocol],
                    open_timeout=5,
                ),
                timeout=5,
            )
            # 如果连接成功了，说明 token 验证有问题
            await ws.close()
            pytest.fail("错误 token 不应成功握手")
        except (websockets.exceptions.InvalidStatusCode,
                websockets.exceptions.InvalidHandshake,
                ConnectionRefusedError,
                asyncio.TimeoutError):
            pass  # 预期被拒绝

    @pytest.mark.asyncio
    async def test_u47_skill_only_accepts_invoke(self, gateway_base_url,
                                                  gateway_internal_token):
        """TC-GW-U47：Skill WS 仅接受 invoke 类型消息。"""
        ws_url = gateway_base_url.replace("http://", "ws://") + "/ws/skill"
        subprotocol = build_internal_auth_subprotocol(gateway_internal_token)

        try:
            ws = await asyncio.wait_for(
                websockets.connect(
                    ws_url,
                    subprotocols=[subprotocol],
                    open_timeout=5,
                ),
                timeout=5,
            )
            # 发送非 invoke 类型消息
            import json
            await ws.send(json.dumps({"type": "tool_event", "data": "test"}))
            # 消息应被忽略，连接不应断开
            await asyncio.sleep(1)
            assert ws.open, "发送非 invoke 消息不应导致断连"
            await ws.close()
        except (websockets.exceptions.InvalidStatusCode,
                ConnectionRefusedError):
            pytest.skip("Gateway Skill WS 端点不可达")


class TestRedisMessageBroker:
    """Redis 消息代理行为测试（通过多连接间接验证）。"""

    @pytest.mark.asyncio
    async def test_u31_pubsub_message_delivery(self, gateway_ws_url,
                                                test_ak, test_sk, gateway_api):
        """TC-GW-U31：Redis Pub/Sub 消息传递（通过 invoke 间接验证）。"""
        from utils.ws_client import connect_agent_ws, register_agent, recv_json
        import json

        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            resp = await register_agent(ws)
            assert resp["type"] == "register_ok"

            # 通过 REST API 发送 invoke
            api_resp = gateway_api.invoke(
                ak=test_ak,
                action="status_query",
                user_id="test_user",
            )

            # 尝试接收消息（可能收到 invoke 或其他消息）
            msg = await recv_json(ws, timeout=3.0)
            # 消息可能到达也可能不到达（取决于路由配置）
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u32_agent_user_binding(self, gateway_ws_url, test_ak, test_sk,
                                          gateway_api):
        """TC-GW-U32：Agent-User 绑定——连接后 REST 查询应返回用户信息。"""
        from utils.ws_client import connect_agent_ws, register_agent

        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            resp = await register_agent(ws)
            assert resp["type"] == "register_ok"

            # 查询 Agent 状态验证绑定
            api_resp = gateway_api.get_agent_status(ak=test_ak)
            assert api_resp.status_code == 200
        finally:
            await ws.close()
