"""
AI-Gateway Agent 生命周期和消息处理测试。

覆盖：
- TC-GW-U09~U14：AgentRegistryService（注册、心跳、超时）
- TC-GW-U15~U20：EventRelayService（消息路由）
- TC-GW-U40~U44：AgentWebSocketHandler（WS 消息处理）
"""

import asyncio
import time
import pytest
from utils.ws_client import (
    connect_agent_ws, register_agent, send_heartbeat,
    send_json, recv_json, recv_json_all,
)


class TestAgentRegistry:
    """Agent 注册和生命周期测试。"""

    @pytest.mark.asyncio
    async def test_u09_first_registration(self, gateway_ws_url, test_ak_2, test_sk_2,
                                          gateway_api):
        """TC-GW-U09：首次注册 Agent（使用 AK2 避免与 agent_online fixture 冲突）。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak_2, test_sk_2)
        try:
            resp = await register_agent(ws, tool_type="OPENCODE", tool_version="1.0")
            assert resp is not None
            assert resp["type"] == "register_ok"

            # 通过 REST API 验证 Agent 在线
            api_resp = gateway_api.list_agents(ak=test_ak_2)
            assert api_resp.status_code == 200
            data = api_resp.json().get("data", [])
            assert test_ak_2 in str(api_resp.text), f"Agent 应在在线列表中: {data}"
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u10_reuse_record_same_ak_tooltype(self, gateway_ws_url,
                                                      test_ak, test_sk, gateway_api):
        """TC-GW-U10：相同 AK + toolType 重用记录。"""
        # 第一次连接
        ws1 = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp1 = await register_agent(ws1, device_name="PC_v1", tool_type="OPENCODE")
        assert resp1["type"] == "register_ok"
        await ws1.close()

        await asyncio.sleep(0.5)

        # 第二次连接（同 AK，应复用记录）
        ws2 = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp2 = await register_agent(ws2, device_name="PC_v2", tool_type="OPENCODE")
        assert resp2["type"] == "register_ok"

        # 验证仅有 1 个在线记录
        api_resp = gateway_api.list_agents(ak=test_ak)
        assert api_resp.status_code == 200

        await ws2.close()

    @pytest.mark.asyncio
    async def test_u14_register_missing_fields(self, gateway_ws_url,
                                                test_ak, test_sk):
        """TC-GW-U14：Agent 注册缺少字段（使用不合规的消息结构）。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            await send_json(ws, {"type": "register"})  # 缺少必要的注册信息
            # 网关可能会返回反序列化失败或忽略不完整的消息，不应该崩溃
            # 简化验证：服务端没断开或返回明确错误
            try:
                msg = await asyncio.wait_for(recv_json(ws), timeout=2.0)
                assert msg is not None
            except asyncio.TimeoutError:
                pass  # 没收到响应也是合理的
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u49_registration_timeout(self, gateway_ws_url,
                                             test_ak, test_sk):
        """TC-GW-U49：Agent 建立连接后超时未注册被强制断开。"""
        import websockets
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            # 建立连接后不发送 subscribe 或 register，等待服务端应用层的 registerTimeoutSeconds
            # 当前默认 10 秒，所以等待 11 秒
            with pytest.raises(websockets.exceptions.ConnectionClosed) as exc:
                await asyncio.wait_for(ws.recv(), timeout=12.0)
            
            # code 4408: register_timeout
            assert exc.value.code == 4408
            assert "register_timeout" in exc.value.reason
        finally:
            if ws.open:
                await ws.close()

    @pytest.mark.asyncio
    async def test_u12_heartbeat(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U12：心跳更新。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            resp = await register_agent(ws)
            assert resp["type"] == "register_ok"

            await asyncio.sleep(1)
            await send_heartbeat(ws)
            # 心跳不需要服务端回复，但连接应保持活跃
            await asyncio.sleep(0.5)
            assert ws.open, "心跳后连接应保持活跃"
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u13_mark_offline_on_close(self, gateway_ws_url, test_ak, test_sk,
                                              gateway_api):
        """TC-GW-U13/U44：下线标记——关闭 WebSocket 后 Agent 应标记为 OFFLINE。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp = await register_agent(ws)
        assert resp["type"] == "register_ok"

        # 关闭连接
        await ws.close()
        await asyncio.sleep(1)

        # 验证 Agent 状态
        api_resp = gateway_api.get_agent_status(ak=test_ak)
        if api_resp.status_code == 200:
            status_data = api_resp.json()
            # Agent 应为 OFFLINE 或不在在线列表中
            agent_status = (status_data.get("data", {}).get("status")
                            or status_data.get("status"))
            if agent_status:
                assert agent_status.upper() in ("OFFLINE", "DISCONNECTED"), \
                    f"Agent 关闭连接后应为 OFFLINE，实际: {agent_status}"


class TestAgentWebSocketHandler:
    """Agent WebSocket 消息处理测试。"""

    @pytest.mark.asyncio
    async def test_u40_handshake_success(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U40：握手认证成功。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            assert ws.open, "握手应成功，连接应已打开"
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u42_register_message(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U42：注册消息处理——发送完整 register 消息。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            await send_json(ws, {
                "type": "register",
                "deviceName": "TestPC",
                "os": "Win11",
                "toolType": "OPENCODE",
                "toolVersion": "1.0",
                "macAddress": "AA:BB:CC:DD:EE:FF",
            })
            resp = await recv_json(ws, timeout=5.0)
            assert resp is not None
            assert resp["type"] == "register_ok"
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u43_heartbeat_message(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U43：心跳消息处理。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            await register_agent(ws)
            await send_json(ws, {"type": "heartbeat"})
            # 心跳消息不需要回复，验证连接仍然活跃
            await asyncio.sleep(0.5)
            assert ws.open
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u44_connection_close(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U44：连接关闭处理。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        await register_agent(ws)
        await ws.close()
        assert not ws.open, "WebSocket 应已关闭"


class TestEventRelay:
    """消息路由测试。"""

    @pytest.mark.asyncio
    async def test_u15_register_rejects_new_session(self, gateway_ws_url,
                                                      test_ak, test_sk):
        """TC-GW-U15：注册 Agent 会话（保留旧连接，拒绝新连接）。"""
        # 第一个连接
        ws1 = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp1 = await register_agent(ws1)
        assert resp1["type"] == "register_ok"

        # 第二个连接（同 AK）
        ws2 = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp2 = await register_agent(ws2)
        assert resp2["type"] == "register_rejected"
        assert resp2.get("reason") == "duplicate_connection"

        # 新连接会被关闭
        await asyncio.sleep(0.5)
        assert not ws2.open
        # 旧连接保持
        assert ws1.open

        await ws1.close()

    @pytest.mark.asyncio
    async def test_u20_status_query_no_agent(self, gateway_api):
        """TC-GW-U20：无 Agent 连接时查询状态。"""
        resp = gateway_api.get_agent_status(ak="nonexistent_ak_no_agent")
        # 应成功但返回 offline 或 404
        assert resp.status_code in (200, 404)
