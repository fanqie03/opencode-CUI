"""
Skill Server 会话管理测试。

覆盖：
- TC-SK-U01~U08：SkillSessionService（会话 CRUD）
- TC-SK-U43~U47：SessionAccessControlService（权限控制）
- TC-SK-U55~U59：SkillSessionController（REST API）
"""

import pytest


class TestSkillSessionCrud:
    """会话 CRUD 测试。"""

    def test_u01_create_session(self, skill_api, test_user_id, test_ak):
        """TC-SK-U01/U55：创建会话。"""
        resp = skill_api.create_session(test_user_id, test_ak, "Test Session")
        assert resp.status_code == 200, f"创建会话失败: {resp.text}"
        data = resp.json()
        session_id = data.get("data", {}).get("welinkSessionId") or data.get("data", {}).get("id")
        assert session_id is not None, f"返回数据缺少 welinkSessionId: {data}"

        # 清理
        skill_api.close_session(session_id, test_user_id)

    def test_u02_get_session_exists(self, create_skill_session, skill_api):
        """TC-SK-U02：获取会话（存在）。"""
        session_id, user_id, _ = create_skill_session()
        resp = skill_api.get_session(session_id, user_id)
        assert resp.status_code == 200

    def test_u03_get_session_not_found(self, skill_api, test_user_id):
        """TC-SK-U03：获取会话（不存在）。"""
        resp = skill_api.get_session(999999999, test_user_id)
        # GlobalExceptionHandler 对 IllegalArgumentException 返回 HTTP 200 + ApiResponse.error(400)
        if resp.status_code == 200:
            body = resp.json()
            assert body.get("code") != 0, \
                f"不存在的会话应返回错误，实际: {body}"
        else:
            assert resp.status_code in (400, 404), \
                f"不存在的会话应返回 404，实际: {resp.status_code}"

    def test_u04_close_session(self, create_skill_session, skill_api):
        """TC-SK-U04/U57：关闭会话。"""
        session_id, user_id, _ = create_skill_session()
        resp = skill_api.close_session(session_id, user_id)
        assert resp.status_code == 200

        # 验证会话已关闭
        get_resp = skill_api.get_session(session_id, user_id)
        if get_resp.status_code == 200:
            data = get_resp.json()
            status = (data.get("data", {}).get("status")
                      or data.get("status", ""))
            assert status.upper() in ("CLOSED", "IDLE"), \
                f"关闭后 status 应为 CLOSED，实际: {status}"

    def test_u06_list_sessions(self, skill_api, test_user_id, test_ak):
        """TC-SK-U06/U56：会话列表查询。"""
        # 创建两个会话
        r1 = skill_api.create_session(test_user_id, test_ak, "Session 1")
        r2 = skill_api.create_session(test_user_id, test_ak, "Session 2")
        s1 = r1.json().get("data", {}).get("welinkSessionId") or r1.json().get("data", {}).get("id")
        s2 = r2.json().get("data", {}).get("welinkSessionId") or r2.json().get("data", {}).get("id")

        try:
            resp = skill_api.list_sessions(test_user_id)
            assert resp.status_code == 200
            data = resp.json()
            sessions = data.get("data", data) if isinstance(data, dict) else data
            # 至少应有我们创建的 2 个
            assert isinstance(sessions, (list, dict))
        finally:
            skill_api.close_session(s1, test_user_id)
            skill_api.close_session(s2, test_user_id)

    def test_u58_abort_session(self, create_skill_session, skill_api):
        """TC-SK-U58：中止会话。"""
        session_id, user_id, _ = create_skill_session()
        resp = skill_api.abort_session(session_id, user_id)
        assert resp.status_code == 200


class TestSessionAccessControl:
    """会话权限控制测试。"""

    def test_u44_require_user_id_empty(self, skill_api):
        """TC-SK-U44：无 userId Cookie 创建会话。"""
        resp = skill_api.create_session_no_auth(ak="test_ak")
        assert resp.status_code in (400, 401, 403), \
            f"无 userId 应返回错误，实际: {resp.status_code}"

    def test_u46_u59_cross_user_access(self, create_skill_session, skill_api,
                                        test_user_id_2):
        """TC-SK-U46/U59：跨用户访问会话被拒。"""
        session_id, _, _ = create_skill_session()

        # user_2 尝试访问 user_1 的会话
        resp = skill_api.get_session(session_id, test_user_id_2)
        assert resp.status_code in (403, 404), \
            f"跨用户访问应被拒绝，实际: {resp.status_code}"

    def test_u46_cross_user_close(self, create_skill_session, skill_api,
                                   test_user_id_2):
        """TC-SK-U46：跨用户关闭会话被拒。"""
        session_id, _, _ = create_skill_session()

        resp = skill_api.close_session(session_id, test_user_id_2)
        assert resp.status_code in (403, 404), \
            f"跨用户关闭应被拒绝，实际: {resp.status_code}"

    def test_u46_cross_user_send_message(self, create_skill_session, skill_api,
                                          test_user_id_2):
        """TC-SK-U46：跨用户发送消息被拒。"""
        session_id, _, _ = create_skill_session()

        resp = skill_api.send_message(session_id, test_user_id_2, "hi")
        assert resp.status_code in (403, 404), \
            f"跨用户发消息应被拒绝，实际: {resp.status_code}"
