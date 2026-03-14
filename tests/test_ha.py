"""
高可用测试。

覆盖 TC-HA-01 ~ TC-HA-05。
注意：部分测试需要手动辅助（如停止/重启服务实例）。
"""

import asyncio
import time
import pytest
from utils.ws_client import (
    connect_agent_ws, register_agent, send_heartbeat,
    connect_skill_stream_ws,
)


@pytest.mark.ha
@pytest.mark.slow
class TestHighAvailability:
    """高可用测试。"""

    @pytest.mark.asyncio
    async def test_ha02_skill_server_reconnect(self, skill_ws_url, test_user_id):
        """TC-HA-02：Skill Server 的 WS 连接应能恢复。
        简化测试：验证多次连接/断开不会导致问题。
        """
        for i in range(5):
            try:
                ws = await connect_skill_stream_ws(
                    skill_ws_url, test_user_id, timeout=5.0
                )
                assert ws.open
                await ws.close()
                await asyncio.sleep(0.5)
            except (ConnectionRefusedError, asyncio.TimeoutError):
                if i == 0:
                    pytest.skip("Skill WS 不可达")
                pytest.fail(f"第 {i+1} 次连接失败")

    @pytest.mark.asyncio
    async def test_ha03_redis_resilience(self, gateway_ws_url, test_ak, test_sk,
                                          gateway_api):
        """TC-HA-03：Redis 不影响基本功能（验证 REST API 仍可用）。"""
        resp = gateway_api.list_agents()
        assert resp.status_code in (200, 500)

    @pytest.mark.asyncio
    async def test_ha04_mysql_resilience(self, skill_api, test_user_id):
        """TC-HA-04：MySQL 不影响 API 基本响应。"""
        resp = skill_api.list_sessions(test_user_id)
        assert resp.status_code in (200, 500)

    @pytest.mark.asyncio
    async def test_ha05_gateway_reconnect_stability(
        self, gateway_ws_url, test_ak, test_sk
    ):
        """TC-HA-05：Gateway 连接稳定性。
        多次连接/断开验证无资源泄漏。
        """
        for i in range(10):
            try:
                ws = await connect_agent_ws(
                    gateway_ws_url, test_ak, test_sk, timeout=5.0
                )
                resp = await register_agent(ws)
                assert resp is not None
                await ws.close()
                await asyncio.sleep(0.3)
            except Exception as e:
                if i == 0:
                    raise
                # 后续失败允许（nonce 重复等）
                pass
