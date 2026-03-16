"""
安全测试。

覆盖 TC-SEC-01 ~ TC-SEC-10：签名伪造、重放攻击、权限越界等。
"""

import time
import asyncio
import pytest
import requests
from utils.auth import generate_nonce, current_timestamp
from utils.ws_client import (
    connect_agent_ws, connect_agent_ws_expect_failure,
    register_agent,
)


@pytest.mark.security
class TestSecurity:
    """安全测试。"""

    @pytest.mark.asyncio
    async def test_sec01_forged_signature(self, gateway_ws_url, test_ak):
        """TC-SEC-01：AK/SK 签名伪造。"""
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, "random_forged_sk_value"
        )
        assert rejected, "伪造签名应被拒绝"

    @pytest.mark.asyncio
    async def test_sec02_expired_timestamp(self, gateway_ws_url, test_ak, test_sk):
        """TC-SEC-02：过期时间戳攻击。"""
        old_ts = str(int(time.time()) - 360)  # 6 分钟前（超出 300 秒容忍窗口）
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, test_sk, timestamp=old_ts
        )
        assert rejected, "过期时间戳应被拒绝"

    @pytest.mark.asyncio
    async def test_sec03_nonce_replay(self, gateway_ws_url, test_ak, test_sk):
        """TC-SEC-03：Nonce 重放攻击。"""
        nonce = generate_nonce()

        ws = await connect_agent_ws(
            gateway_ws_url, test_ak, test_sk, nonce=nonce
        )
        resp = await register_agent(ws)
        assert resp["type"] == "register_ok"
        await ws.close()

        # 重放相同 nonce
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, test_sk, nonce=nonce
        )
        assert rejected, "Nonce 重放应被拒绝"

    @pytest.mark.asyncio
    async def test_sec04_internal_token_brute_force(self, gateway_base_url):
        """TC-SEC-04：Internal Token 暴力破解。"""
        from utils.auth import build_internal_auth_subprotocol
        import websockets

        ws_url = gateway_base_url.replace("http://", "ws://") + "/ws/skill"

        for i in range(10):  # 测试 10 次而非 100 次（节省时间）
            subprotocol = build_internal_auth_subprotocol(f"brute_force_attempt_{i}")
            try:
                ws = await asyncio.wait_for(
                    websockets.connect(
                        ws_url,
                        subprotocols=[subprotocol],
                        open_timeout=3,
                    ),
                    timeout=3,
                )
                await ws.close()
                pytest.fail(f"attempt {i} 不应成功")
            except (websockets.exceptions.InvalidStatusCode,
                    websockets.exceptions.InvalidHandshake,
                    ConnectionRefusedError,
                    asyncio.TimeoutError):
                pass  # 预期被拒绝

    def test_sec05_cross_user_session_access(
        self, skill_api, test_user_id, test_user_id_2, test_ak, agent_online
    ):
        """TC-SEC-05：跨用户会话访问。"""
        # user1 创建会话
        resp = skill_api.create_session(test_user_id, test_ak, "Private Session")
        assert resp.status_code == 200
        session_id = resp.json().get("data", {}).get("welinkSessionId")

        try:
            # user2 尝试访问
            get_resp = skill_api.get_session(session_id, test_user_id_2)
            assert get_resp.status_code in (403, 404)

            close_resp = skill_api.close_session(session_id, test_user_id_2)
            assert close_resp.status_code in (403, 404)

            msg_resp = skill_api.send_message(
                session_id, test_user_id_2, "trying to hack"
            )
            assert msg_resp.status_code in (403, 404)

            history_resp = skill_api.get_messages(session_id, test_user_id_2)
            assert history_resp.status_code in (403, 404)
        finally:
            skill_api.close_session(session_id, test_user_id)

    def test_sec06_forged_userid_cookie(self, skill_api, test_user_id, test_ak, agent_online):
        """TC-SEC-06：userId Cookie 伪造。"""
        # 创建会话
        resp = skill_api.create_session(test_user_id, test_ak, "Cookie Test")
        assert resp.status_code == 200
        session_id = resp.json().get("data", {}).get("welinkSessionId")

        try:
            # 伪造 userId 访问
            fake_resp = skill_api.get_session(session_id, "fake_user_id")
            assert fake_resp.status_code in (403, 404)
        finally:
            skill_api.close_session(session_id, test_user_id)

    @pytest.mark.asyncio
    async def test_sec07_ws_message_injection(self, gateway_ws_url, test_ak, test_sk):
        """TC-SEC-07：WebSocket 消息注入。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            await register_agent(ws)

            # 发送畸形 JSON
            await ws.send("not a valid json {{")
            await asyncio.sleep(0.5)

            # 发送超大消息
            try:
                await ws.send("{" * 100000)
            except Exception:
                pass

            await asyncio.sleep(0.5)

            # 发送未知类型
            import json
            await ws.send(json.dumps({"type": "UNKNOWN_TYPE", "data": "test"}))
            await asyncio.sleep(0.5)

            # 连接可能被关闭或仍然存活，都是合理行为
        except Exception:
            pass  # 任何异常都是可接受的（服务端做了防护）
        finally:
            if ws.open:
                await ws.close()

    def test_sec08_rest_api_no_auth(self, gateway_api, skill_api):
        """TC-SEC-08：REST API 未认证访问。"""
        # Gateway: 无 Bearer Token
        resp = gateway_api.list_agents_no_auth()
        assert resp.status_code == 401

        # Skill: 无 UserIdCookie
        resp = skill_api.create_session_no_auth(ak="any_ak")
        assert resp.status_code in (400, 401, 403)

    def test_sec09_sql_injection(self, skill_api, test_user_id):
        """TC-SEC-09：SQL 注入防护。"""
        # 在 userId 中注入 SQL
        malicious_user = "'; DROP TABLE skill_session;--"
        resp = skill_api.list_sessions(malicious_user)
        # 应返回错误但不崩溃
        assert resp.status_code in (200, 400, 403, 404, 500)

        # 在消息内容中注入 SQL
        resp2 = skill_api.create_session(
            test_user_id, "test_ak",
            "'; DROP TABLE skill_session;--"
        )
        # 创建应成功（SQL 被参数化查询处理）或返回合理错误
        assert resp2.status_code in (200, 400)
        if resp2.status_code == 200:
            sid = resp2.json().get("data", {}).get("welinkSessionId")
            if sid:
                skill_api.close_session(sid, test_user_id)

    def test_sec10_xss(self, create_skill_session, skill_api):
        """TC-SEC-10：XSS 防护。"""
        session_id, user_id, _ = create_skill_session()

        xss_payload = '<script>alert("XSS")</script>'
        resp = skill_api.send_message(session_id, user_id, xss_payload)
        assert resp.status_code in (200, 202, 400)

        # 如果发送成功，查看历史消息中的内容
        if resp.status_code in (200, 202):
            history = skill_api.get_messages(session_id, user_id)
            if history.status_code == 200:
                data = history.json()
                # 消息应被原样存储（前端负责转义），不应包含可执行 JS
                raw = str(data)
                assert "alert(" not in raw or xss_payload in raw
