"""
REST API 客户端封装。

提供 Gateway 和 Skill Server 的 HTTP API 调用工具。
"""

from typing import Optional
import requests


class GatewayApiClient:
    """AI-Gateway REST API 客户端。"""

    def __init__(self, base_url: str, internal_token: str):
        self.base_url = base_url.rstrip("/")
        self.internal_token = internal_token

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.internal_token}"}

    def list_agents(self, ak: str = None, user_id: str = None) -> requests.Response:
        """GET /api/gateway/agents"""
        params = {}
        if ak:
            params["ak"] = ak
        if user_id:
            params["userId"] = user_id
        return requests.get(
            f"{self.base_url}/api/gateway/agents",
            params=params,
            headers=self._headers(),
        )

    def list_agents_no_auth(self) -> requests.Response:
        """GET /api/gateway/agents（无认证）"""
        return requests.get(f"{self.base_url}/api/gateway/agents")

    def get_agent_status(self, ak: str) -> requests.Response:
        """GET /api/gateway/agents/status"""
        return requests.get(
            f"{self.base_url}/api/gateway/agents/status",
            params={"ak": ak},
            headers=self._headers(),
        )

    def invoke(self, ak: str, action: str, payload: dict = None,
               user_id: str = None, session_id: str = None) -> requests.Response:
        """POST /api/gateway/invoke"""
        body = {"ak": ak, "action": action}
        if payload:
            body["payload"] = payload
        if user_id:
            body["userId"] = user_id
        if session_id:
            body["sessionId"] = session_id
        return requests.post(
            f"{self.base_url}/api/gateway/invoke",
            json=body,
            headers=self._headers(),
        )

    def invoke_no_ak(self, action: str) -> requests.Response:
        """POST /api/gateway/invoke（无 ak 字段）"""
        return requests.post(
            f"{self.base_url}/api/gateway/invoke",
            json={"action": action},
            headers=self._headers(),
        )


class SkillApiClient:
    """Skill Server REST API 客户端。"""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def _cookies(self, user_id: str) -> dict:
        return {"userId": user_id}

    # ==================== Sessions ====================

    def create_session(self, user_id: str, ak: str,
                       title: str = "Test Session",
                       business_session_id: str = "default_group",
                       business_session_domain: str = "miniapp",
                       business_session_type: str = "direct",
                       assistant_account: Optional[str] = None) -> requests.Response:
        """POST /api/skill/sessions"""
        body = {
            "ak": ak,
            "title": title,
            "businessSessionDomain": business_session_domain,
            "businessSessionType": business_session_type,
        }
        if business_session_id:
            body["businessSessionId"] = business_session_id
        if assistant_account:
            body["assistantAccount"] = assistant_account
        return requests.post(
            f"{self.base_url}/api/skill/sessions",
            json=body,
            cookies=self._cookies(user_id),
        )

    def list_sessions(self, user_id: str, status: str = None,
                      page: int = 0, size: int = 20) -> requests.Response:
        """GET /api/skill/sessions"""
        params = {"page": page, "size": size}
        if status:
            params["status"] = status
        return requests.get(
            f"{self.base_url}/api/skill/sessions",
            params=params,
            cookies=self._cookies(user_id),
        )

    def get_session(self, session_id, user_id: str) -> requests.Response:
        """GET /api/skill/sessions/{id}"""
        return requests.get(
            f"{self.base_url}/api/skill/sessions/{session_id}",
            cookies=self._cookies(user_id),
        )

    def close_session(self, session_id, user_id: str) -> requests.Response:
        """DELETE /api/skill/sessions/{id}"""
        return requests.delete(
            f"{self.base_url}/api/skill/sessions/{session_id}",
            cookies=self._cookies(user_id),
        )

    def abort_session(self, session_id, user_id: str) -> requests.Response:
        """POST /api/skill/sessions/{id}/abort"""
        return requests.post(
            f"{self.base_url}/api/skill/sessions/{session_id}/abort",
            cookies=self._cookies(user_id),
        )

    # ==================== Messages ====================

    def send_message(self, session_id, user_id: str,
                     content: str) -> requests.Response:
        """POST /api/skill/sessions/{id}/messages"""
        return requests.post(
            f"{self.base_url}/api/skill/sessions/{session_id}/messages",
            json={"content": content},
            cookies=self._cookies(user_id),
        )

    def get_messages(self, session_id, user_id: str,
                     page: int = 0, size: int = 20) -> requests.Response:
        """GET /api/skill/sessions/{id}/messages"""
        return requests.get(
            f"{self.base_url}/api/skill/sessions/{session_id}/messages",
            params={"page": page, "size": size},
            cookies=self._cookies(user_id),
        )

    def get_message_history(self, session_id, user_id: str,
                            size: int = 50, before_seq: Optional[int] = None) -> requests.Response:
        """GET /api/skill/sessions/{id}/messages/history"""
        params = {"size": size}
        if before_seq is not None:
            params["beforeSeq"] = before_seq
        return requests.get(
            f"{self.base_url}/api/skill/sessions/{session_id}/messages/history",
            params=params,
            cookies=self._cookies(user_id),
        )

    def send_to_im(self, session_id, user_id: str,
                   content: str, chat_id: str) -> requests.Response:
        """POST /api/skill/sessions/{id}/send-to-im"""
        return requests.post(
            f"{self.base_url}/api/skill/sessions/{session_id}/send-to-im",
            json={"content": content, "chatId": chat_id},
            cookies=self._cookies(user_id),
        )

    def permission_reply(self, session_id, user_id: str,
                         perm_id: str, response_val: str) -> requests.Response:
        """POST /api/skill/sessions/{id}/permissions/{permId}"""
        return requests.post(
            f"{self.base_url}/api/skill/sessions/{session_id}/permissions/{perm_id}",
            json={"response": response_val},
            cookies=self._cookies(user_id),
        )

    # ==================== Agents ====================

    def query_agents(self, user_id: str) -> requests.Response:
        """GET /api/skill/agents"""
        return requests.get(
            f"{self.base_url}/api/skill/agents",
            cookies=self._cookies(user_id),
        )

    # ==================== No Auth ====================

    def create_session_no_auth(self, ak: str) -> requests.Response:
        """POST /api/skill/sessions（无 userId Cookie）"""
        return requests.post(
            f"{self.base_url}/api/skill/sessions",
            json={"ak": ak, "title": "No auth"},
        )
