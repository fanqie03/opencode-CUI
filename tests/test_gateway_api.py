"""
AI-Gateway REST API 测试。

覆盖 TC-GW-U21 ~ TC-GW-U27：AgentController 端点。
"""

import pytest


class TestGatewayAgentApi:
    """Gateway Agent REST API 测试。"""

    def test_u21_list_online_agents(self, gateway_api):
        """TC-GW-U21：GET /api/gateway/agents — 列出在线 Agent。"""
        resp = gateway_api.list_agents()
        assert resp.status_code == 200
        data = resp.json()
        # data 应为列表或包含 data 字段
        assert isinstance(data, (dict, list))

    def test_u22_list_agents_filter_by_ak(self, gateway_api, test_ak):
        """TC-GW-U22：GET /api/gateway/agents — 按 AK 过滤。"""
        resp = gateway_api.list_agents(ak=test_ak)
        assert resp.status_code == 200

    def test_u23_list_agents_no_auth(self, gateway_api):
        """TC-GW-U23：GET /api/gateway/agents — 未授权访问。"""
        resp = gateway_api.list_agents_no_auth()
        assert resp.status_code == 401, \
            f"无认证应返回 401，实际: {resp.status_code}"

    def test_u24_get_agent_status(self, gateway_api, test_ak):
        """TC-GW-U24：GET /api/gateway/agents/status — 查询 Agent 状态。"""
        resp = gateway_api.get_agent_status(ak=test_ak)
        assert resp.status_code in (200, 404)
        if resp.status_code == 200:
            data = resp.json()
            # 应包含状态信息
            assert "data" in data or "status" in data or "ak" in data

    def test_u25_invoke_agent(self, gateway_api, test_ak, test_user_id):
        """TC-GW-U25：POST /api/gateway/invoke — 发送指令。
        注意：此测试需要 Agent 在线，否则预期返回错误。
        """
        resp = gateway_api.invoke(
            ak=test_ak,
            action="chat",
            payload={"content": "test"},
            user_id=test_user_id,
        )
        # Agent 在线返回 200，不在线返回 400/404
        assert resp.status_code in (200, 400, 404)

    def test_u26_invoke_offline_agent(self, gateway_api):
        """TC-GW-U26：POST /api/gateway/invoke — Agent 离线。"""
        resp = gateway_api.invoke(
            ak="definitely_offline_agent_ak",
            action="chat",
        )
        assert resp.status_code in (400, 404), \
            f"离线 Agent 应返回 400/404，实际: {resp.status_code}"

    def test_u27_invoke_missing_ak(self, gateway_api):
        """TC-GW-U27：POST /api/gateway/invoke — 缺少 AK。"""
        resp = gateway_api.invoke_no_ak(action="chat")
        assert resp.status_code == 400, \
            f"缺少 AK 应返回 400，实际: {resp.status_code}"
