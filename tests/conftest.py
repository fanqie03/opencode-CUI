"""
pytest 全局 fixtures 和配置。

从 .env.test 加载测试环境变量，提供共享的 API 客户端和工具方法。
"""

import os
import pytest
from dotenv import load_dotenv
from utils.api_client import GatewayApiClient, SkillApiClient

# 加载测试环境变量
_env_path = os.path.join(os.path.dirname(__file__), ".env.test")
load_dotenv(_env_path)


# ==================== 环境变量 Fixtures ====================

@pytest.fixture(scope="session")
def gateway_base_url():
    return os.getenv("GATEWAY_BASE_URL", "http://localhost:8081")


@pytest.fixture(scope="session")
def gateway_ws_url():
    return os.getenv("GATEWAY_WS_URL", "ws://localhost:8081/ws/agent")


@pytest.fixture(scope="session")
def gateway_internal_token():
    return os.getenv("GATEWAY_INTERNAL_TOKEN", "sk-intl-9f2a7d3e4b1c")


@pytest.fixture(scope="session")
def skill_base_url():
    return os.getenv("SKILL_BASE_URL", "http://localhost:8082")


@pytest.fixture(scope="session")
def skill_ws_url():
    return os.getenv("SKILL_WS_URL", "ws://localhost:8082/ws/skill/stream")


@pytest.fixture(scope="session")
def test_ak():
    return os.getenv("TEST_AK", "test_ak_001")


@pytest.fixture(scope="session")
def test_sk():
    return os.getenv("TEST_SK", "test_sk_001")


@pytest.fixture(scope="session")
def test_user_id():
    return os.getenv("TEST_USER_ID", "test_user_001")


@pytest.fixture(scope="session")
def test_ak_2():
    return os.getenv("TEST_AK_2", "test_ak_002")


@pytest.fixture(scope="session")
def test_sk_2():
    return os.getenv("TEST_SK_2", "test_sk_002")


@pytest.fixture(scope="session")
def test_user_id_2():
    return os.getenv("TEST_USER_ID_2", "test_user_002")


@pytest.fixture(scope="session")
def test_im_chat_id():
    return os.getenv("TEST_IM_CHAT_ID", "test_chat_001")


# ==================== API 客户端 Fixtures ====================

@pytest.fixture(scope="session")
def gateway_api(gateway_base_url, gateway_internal_token):
    """Gateway REST API 客户端。"""
    return GatewayApiClient(gateway_base_url, gateway_internal_token)


@pytest.fixture(scope="session")
def skill_api(skill_base_url):
    """Skill Server REST API 客户端。"""
    return SkillApiClient(skill_base_url)


# ==================== 辅助 Fixtures ====================

@pytest.fixture
def agent_online(gateway_ws_url, test_ak, test_sk):
    """
    在单个测试期间保持一个 Agent WebSocket 连接在线。

    SessionAccessControlService.requireSessionAccess() 不仅验证 userId，
    还通过 GatewayApiClient.isAkOwnedByUser() 检查该 AK 是否有在线 Agent。
    如果没有 Agent 在线，所有带 AK 的 session 操作都返回 403。

    使用 function scope 避免长期占用 AK 导致其他测试的 register 被保旧拒新策略拒绝。
    """
    import asyncio
    from utils.ws_client import connect_agent_ws, register_agent

    loop = asyncio.new_event_loop()

    async def _connect():
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        resp = await register_agent(ws)
        assert resp is not None and resp.get("type") == "register_ok", \
            f"Agent 注册失败: {resp}"
        return ws

    ws = loop.run_until_complete(_connect())
    yield ws

    try:
        loop.run_until_complete(ws.close())
    except Exception:
        pass
    # 等待 Gateway 释放旧连接
    import time
    time.sleep(0.5)
    loop.close()


@pytest.fixture
def create_skill_session(skill_api, test_user_id, test_ak, agent_online):
    """
    创建一个临时 Skill Session 并在测试结束后清理。

    返回 (session_id, user_id, ak)。
    """
    created_sessions = []

    def _create(user_id=None, ak=None, title="Test Session"):
        uid = user_id or test_user_id
        a = ak or test_ak
        resp = skill_api.create_session(uid, a, title)
        assert resp.status_code == 200, f"创建会话失败: {resp.text}"
        data = resp.json()
        session_id = data.get("data", {}).get("welinkSessionId") or data.get("data", {}).get("id")
        created_sessions.append((session_id, uid))
        return session_id, uid, a

    yield _create

    # 清理：尝试关闭所有创建的会话
    for sid, uid in created_sessions:
        try:
            skill_api.close_session(sid, uid)
        except Exception:
            pass

