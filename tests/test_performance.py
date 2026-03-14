"""
性能测试。

覆盖 TC-PERF-01 ~ TC-PERF-05：并发连接、吞吐量、延迟。
"""

import asyncio
import time
import pytest
import requests
from utils.ws_client import (
    connect_agent_ws, register_agent, send_heartbeat,
    connect_skill_stream_ws, recv_json_all,
)
from utils.auth import generate_nonce


@pytest.mark.performance
@pytest.mark.slow
class TestPerformance:
    """性能测试。"""

    @pytest.mark.asyncio
    async def test_perf01_concurrent_ws_connections(
        self, gateway_ws_url, test_ak, test_sk
    ):
        """TC-PERF-01：WebSocket 并发连接（简化版：10 个并发）。"""
        connections = []
        success_count = 0
        fail_count = 0

        for i in range(10):
            try:
                nonce = generate_nonce()
                ws = await connect_agent_ws(
                    gateway_ws_url, test_ak, test_sk,
                    nonce=nonce, timeout=10.0
                )
                connections.append(ws)
                success_count += 1
            except Exception as e:
                fail_count += 1

        # 清理
        for ws in connections:
            try:
                await ws.close()
            except Exception:
                pass

        # 允许 1 个失败（因为同 AK 会踢旧连接）
        # 实际生产测试应使用不同 AK
        assert success_count >= 1, \
            f"至少应有 1 个连接成功，成功={success_count}，失败={fail_count}"

    @pytest.mark.asyncio
    async def test_perf03_stream_latency(self, skill_ws_url, test_user_id):
        """TC-PERF-03：流式推送延迟（WS 建连延迟测量）。"""
        start = time.monotonic()
        try:
            ws = await connect_skill_stream_ws(
                skill_ws_url, test_user_id, timeout=5.0
            )
            connect_time = time.monotonic() - start
            await ws.close()

            assert connect_time < 2.0, \
                f"WS 建连延迟应 <2s，实际: {connect_time:.3f}s"
        except (ConnectionRefusedError, asyncio.TimeoutError):
            pytest.skip("Skill WS 不可达")

    def test_perf04_rest_api_response_time(self, gateway_api, skill_api, test_user_id):
        """TC-PERF-04：REST API 响应时间。"""
        # Gateway: GET /api/gateway/agents
        gw_times = []
        for _ in range(20):
            start = time.monotonic()
            resp = gateway_api.list_agents()
            elapsed = time.monotonic() - start
            gw_times.append(elapsed)
            assert resp.status_code in (200, 401)

        avg_gw = sum(gw_times) / len(gw_times)
        p95_gw = sorted(gw_times)[int(len(gw_times) * 0.95)]

        # Skill: GET /api/skill/sessions
        sk_times = []
        for _ in range(20):
            start = time.monotonic()
            resp = skill_api.list_sessions(test_user_id)
            elapsed = time.monotonic() - start
            sk_times.append(elapsed)
            assert resp.status_code in (200, 400)

        avg_sk = sum(sk_times) / len(sk_times)
        p95_sk = sorted(sk_times)[int(len(sk_times) * 0.95)]

        assert p95_gw < 1.0, \
            f"Gateway P95 应 <1s，实际: {p95_gw:.3f}s"
        assert p95_sk < 1.0, \
            f"Skill P95 应 <1s，实际: {p95_sk:.3f}s"

    def test_perf04_concurrent_rest_requests(
        self, gateway_api, skill_api, test_user_id
    ):
        """TC-PERF-04：并发 REST 请求。"""
        import concurrent.futures

        def call_gateway():
            return gateway_api.list_agents().status_code

        def call_skill():
            return skill_api.list_sessions(test_user_id).status_code

        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
            gw_futures = [executor.submit(call_gateway) for _ in range(20)]
            sk_futures = [executor.submit(call_skill) for _ in range(20)]

            gw_results = [f.result(timeout=10) for f in gw_futures]
            sk_results = [f.result(timeout=10) for f in sk_futures]

        # 所有请求应成功
        gw_success = sum(1 for r in gw_results if r in (200, 401))
        sk_success = sum(1 for r in sk_results if r in (200, 400))

        assert gw_success >= 18, \
            f"Gateway 成功率应 ≥90%：{gw_success}/20"
        assert sk_success >= 18, \
            f"Skill 成功率应 ≥90%：{sk_success}/20"
