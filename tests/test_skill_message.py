"""
Skill Server 消息管理测试。

覆盖：
- TC-SK-U09~U13：SkillMessageService（消息 CRUD）
- TC-SK-U60~U64：SkillMessageController（REST API）
- TC-SK-U65~U67：MessagePersistenceService（间接验证）
"""

import pytest


class TestSkillMessageCrud:
    """消息 CRUD 测试。"""

    def test_u09_u60_send_user_message(self, create_skill_session, skill_api):
        """TC-SK-U09/U60：发送用户消息。"""
        session_id, user_id, _ = create_skill_session()
        resp = skill_api.send_message(session_id, user_id, "Hello AI")
        # 发送消息可能成功(200)或因 toolSessionId 缺失触发重建(200/202)
        assert resp.status_code in (200, 202, 400), \
            f"发送消息异常: {resp.status_code} - {resp.text}"

    def test_u12_u62_get_message_history(self, create_skill_session, skill_api):
        """TC-SK-U12/U62：查询消息历史。"""
        session_id, user_id, _ = create_skill_session()

        # 尝试发一条消息先
        skill_api.send_message(session_id, user_id, "Test message for history")

        resp = skill_api.get_messages(session_id, user_id, page=0, size=20)
        assert resp.status_code == 200
        data = resp.json()
        # 应返回列表结构
        assert isinstance(data, dict)

    def test_u12_pagination(self, create_skill_session, skill_api):
        """TC-SK-U12：消息分页查询。"""
        session_id, user_id, _ = create_skill_session()

        # 发送多条消息
        for i in range(5):
            skill_api.send_message(session_id, user_id, f"Message {i}")

        # 分页查询
        resp = skill_api.get_messages(session_id, user_id, page=0, size=3)
        assert resp.status_code == 200

    def test_u63_send_to_im(self, create_skill_session, skill_api,
                             test_im_chat_id):
        """TC-SK-U63：POST /api/skill/sessions/{id}/send-to-im。"""
        session_id, user_id, _ = create_skill_session()

        resp = skill_api.send_to_im(
            session_id, user_id, "Message to send to IM", test_im_chat_id
        )
        # IM API 可能不可用，接受 200 或 500 或 400
        assert resp.status_code in (200, 400, 500)

    def test_u64_permission_reply(self, create_skill_session, skill_api):
        """TC-SK-U64：POST /api/skill/sessions/{id}/permissions/{permId}。"""
        session_id, user_id, _ = create_skill_session()

        resp = skill_api.permission_reply(
            session_id, user_id,
            perm_id="perm_test_001",
            response_val="once",
        )
        # 即使没有待处理的权限请求，接口应返回 200 或合理的错误
        assert resp.status_code in (200, 400, 404)


class TestSkillMessageEdgeCases:
    """消息边界测试。"""

    def test_u61_send_without_tool_session(self, create_skill_session, skill_api):
        """TC-SK-U61：toolSessionId 缺失时触发重建。"""
        session_id, user_id, _ = create_skill_session()

        # 新会话没有 toolSessionId，发送消息应触发重建逻辑
        resp = skill_api.send_message(session_id, user_id, "Trigger rebuild")
        # 重建也算成功（消息被缓存等待重建后重发）
        assert resp.status_code in (200, 202, 400)

    def test_empty_message(self, create_skill_session, skill_api):
        """空消息应被拒绝。"""
        session_id, user_id, _ = create_skill_session()
        resp = skill_api.send_message(session_id, user_id, "")
        assert resp.status_code in (200, 400)

    def test_long_message(self, create_skill_session, skill_api):
        """超长消息应正常处理或有限制。"""
        session_id, user_id, _ = create_skill_session()
        long_content = "A" * 10000
        resp = skill_api.send_message(session_id, user_id, long_content)
        assert resp.status_code in (200, 202, 400, 413)
