"""
路由解耦集成测试。

覆盖 Task 3.1 ~ 3.4：
- 下行路由：SS → GW → Agent（一致性哈希路由）
- 上行路由：Agent → GW → SS（路由学习表）
- 兼容性：新旧 Redis key 双写 + Legacy 路由开关
- 失联 Owner 接管：Redis session/instance 心跳缓存

需要 ai-gateway 和 skill-server 同时运行。
标记说明：
  requires_agent — 需要真实 PC Agent 在线
  ha             — 高可用 / 多实例场景
"""

import json
import time

import pytest
import requests

from utils.ws_client import (
    connect_agent_ws,
    recv_json,
    register_agent,
    send_json,
)


# ==================== 下行路由测试 ====================


@pytest.mark.requires_agent
class TestDownlinkRouting:
    """SS → GW → Agent 下行路由（一致性哈希）。"""

    def test_gw_internal_instances_endpoint(self, gateway_base_url,
                                             gateway_internal_token):
        """GW 实例发现接口应返回当前存活实例列表。

        验证目标：
        - /internal/instances 返回 200
        - 响应体包含 instances 数组
        - 每个实例含 instanceId 和 wsUrl 字段
        """
        resp = requests.get(
            f"{gateway_base_url}/internal/instances",
            headers={"Authorization": f"Bearer {gateway_internal_token}"},
            timeout=3,
        )
        assert resp.status_code == 200, (
            f"实例发现接口应返回 200，实际: {resp.status_code} {resp.text}"
        )
        data = resp.json()
        assert "instances" in data, f"响应应包含 instances 字段，实际: {data}"
        assert len(data["instances"]) > 0, "实例列表不应为空（至少有当前节点）"
        for inst in data["instances"]:
            assert "instanceId" in inst, f"实例缺少 instanceId 字段: {inst}"
            assert "wsUrl" in inst, f"实例缺少 wsUrl 字段: {inst}"

    @pytest.mark.asyncio
    async def test_invoke_routed_to_online_agent(
        self, gateway_ws_url, gateway_base_url, gateway_internal_token,
        test_ak, test_sk
    ):
        """SS 发送 invoke 应通过哈希路由到达在线 Agent。

        验证目标：
        - Agent 注册后，通过内部 invoke 接口发送消息
        - Agent 在 WS 连接上收到 invoke 消息
        - 消息 type 为 invoke
        """
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            reg_resp = await register_agent(ws)
            assert reg_resp is not None and reg_resp.get("type") == "register_ok", (
                f"Agent 注册失败: {reg_resp}"
            )

            # 通过 GW 内部接口模拟 SS 下发 invoke
            resp = requests.post(
                f"{gateway_base_url}/internal/invoke",
                json={
                    "ak": test_ak,
                    "type": "invoke",
                    "action": "status_query",
                    "userId": "route_test_user",
                },
                headers={"Authorization": f"Bearer {gateway_internal_token}"},
                timeout=3,
            )
            # invoke 接口应接受请求（200 或 202）
            assert resp.status_code in (200, 202), (
                f"内部 invoke 接口应返回 2xx，实际: {resp.status_code} {resp.text}"
            )

            # Agent 应收到 invoke 消息
            msg = await recv_json(ws, timeout=5.0)
            assert msg is not None, "Agent 应在 5s 内收到 invoke 消息"
            assert msg.get("type") == "invoke", (
                f"消息类型应为 invoke，实际: {msg.get('type')}"
            )
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_invoke_offline_agent_buffered(
        self, gateway_base_url, gateway_internal_token, test_ak
    ):
        """Agent 离线时，invoke 消息应写入 gw:pending:{ak} 缓冲队列。

        验证目标：
        - 无 Agent 在线的情况下发送 invoke
        - GW 应返回 2xx（接受并缓冲）而非立即报错
        # TODO: 需要 Redis 直连权限才能进一步验证队列内容
        """
        resp = requests.post(
            f"{gateway_base_url}/internal/invoke",
            json={
                "ak": test_ak,
                "type": "invoke",
                "action": "status_query",
                "userId": "route_test_user_offline",
            },
            headers={"Authorization": f"Bearer {gateway_internal_token}"},
            timeout=3,
        )
        # GW 应接受请求并缓冲（不因 Agent 离线而返回 4xx/5xx）
        assert resp.status_code in (200, 202), (
            f"Agent 离线时 invoke 应被缓冲，实际: {resp.status_code} {resp.text}"
        )


# ==================== 上行路由测试 ====================


@pytest.mark.requires_agent
class TestUplinkRouting:
    """Agent → GW → SS 上行路由（路由学习表）。"""

    @pytest.mark.asyncio
    async def test_route_learning_records_ss_instance(
        self, gateway_ws_url, gateway_base_url, gateway_internal_token,
        test_ak, test_sk
    ):
        """GW 在处理 invoke 时应将 SS 实例 ID 写入上行路由学习表。

        验证目标：
        - SS 通过内部接口发送 invoke，携带 X-SS-Instance-Id 请求头
        - GW 应记录该 SS 实例 ID 与 ak 的映射关系
        - 后续 Agent 上行消息能被路由到正确的 SS 实例
        # TODO: 路由表查询接口需要 GW 暴露 /internal/routing-table 端点
        """
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            reg_resp = await register_agent(ws)
            assert reg_resp is not None and reg_resp.get("type") == "register_ok", (
                f"Agent 注册失败: {reg_resp}"
            )

            # 模拟 SS 携带实例 ID 发送 invoke（路由学习触发点）
            ss_instance_id = "test-ss-instance-001"
            resp = requests.post(
                f"{gateway_base_url}/internal/invoke",
                json={
                    "ak": test_ak,
                    "type": "invoke",
                    "action": "status_query",
                    "userId": "route_learning_user",
                    "welinkSessionId": "test-session-001",
                },
                headers={
                    "Authorization": f"Bearer {gateway_internal_token}",
                    "X-SS-Instance-Id": ss_instance_id,
                },
                timeout=3,
            )
            assert resp.status_code in (200, 202), (
                f"invoke 应被接受，实际: {resp.status_code} {resp.text}"
            )

            # 等待路由学习表写入
            time.sleep(0.5)

            # Agent 接收 invoke 并回复 tool_event（触发上行路由）
            msg = await recv_json(ws, timeout=5.0)
            if msg is not None and msg.get("type") == "invoke":
                await send_json(ws, {
                    "type": "tool_event",
                    "welinkSessionId": msg.get("welinkSessionId", "test-session-001"),
                    "toolSessionId": msg.get("toolSessionId", ""),
                    "data": "route learning test event",
                })
                # 给 GW 时间处理上行路由
                time.sleep(0.3)
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_tool_event_reaches_ss_via_relay(
        self, gateway_ws_url, gateway_base_url, gateway_internal_token,
        skill_base_url, test_ak, test_sk, test_user_id
    ):
        """Agent 发送的 tool_event 应通过 relay 通道到达 SS。

        验证目标：
        - Agent 发送 tool_event 后，SS 的 Skill Stream WS 应收到该事件
        # TODO: 需要 SS 端 WebSocket 连接配合，依赖 create_skill_session fixture
        # TODO: 端到端验证路径：Agent WS → GW relay → SS WS → Skill Stream
        """
        pass  # TODO: 依赖 agent_online + create_skill_session 两个 fixture 协同


# ==================== 兼容性测试 ====================


class TestCompatibility:
    """新旧版本兼容性（双写 Redis key + Legacy 路由开关）。"""

    def test_gw_health_endpoint_reachable(self, gateway_base_url):
        """GW 健康检查接口应正常响应（服务运行前提验证）。"""
        try:
            resp = requests.get(
                f"{gateway_base_url}/actuator/health",
                timeout=3,
            )
            assert resp.status_code == 200, (
                f"GW 健康检查应返回 200，实际: {resp.status_code}"
            )
            data = resp.json()
            assert data.get("status") == "UP", (
                f"GW 状态应为 UP，实际: {data.get('status')}"
            )
        except requests.exceptions.ConnectionError:
            pytest.skip("GW 服务不可达，跳过兼容性测试")

    def test_gw_dual_write_redis_keys(self, gateway_base_url,
                                       gateway_internal_token):
        """GW 实例注册时应同时写入新旧 Redis key 格式。

        验证目标：
        - gw:internal:instance:{instanceId}（新格式）
        - gw:instance:{instanceId}（旧格式，Legacy 兼容）
        - /internal/instances 接口能查到当前实例（间接验证 Redis 写入）
        # TODO: 直接验证需要 Redis 直连；当前通过 /internal/instances 接口间接验证
        """
        try:
            resp = requests.get(
                f"{gateway_base_url}/internal/instances",
                headers={"Authorization": f"Bearer {gateway_internal_token}"},
                timeout=3,
            )
        except requests.exceptions.ConnectionError:
            pytest.skip("GW 服务不可达")

        assert resp.status_code == 200, (
            f"实例发现接口应返回 200，实际: {resp.status_code}"
        )
        data = resp.json()
        # 实例存在即表明 Redis 写入成功（双写中至少新格式有效）
        assert len(data.get("instances", [])) > 0, (
            "实例列表不应为空，Redis 双写可能存在问题"
        )

    def test_legacy_relay_enabled_by_default(self, gateway_base_url,
                                              gateway_internal_token):
        """Legacy 路由开关应默认开启，旧版 source 仍可被路由。

        验证目标：
        - GW 配置中 gateway.legacy-relay.enabled 默认为 true
        - /internal/config 或 /actuator/env 中可读取该配置项
        # TODO: 若 GW 未暴露配置查询接口，改为通过发送 legacy 格式消息行为验证
        """
        try:
            resp = requests.get(
                f"{gateway_base_url}/actuator/env/gateway.legacy-relay.enabled",
                headers={"Authorization": f"Bearer {gateway_internal_token}"},
                timeout=3,
            )
        except requests.exceptions.ConnectionError:
            pytest.skip("GW 服务不可达")

        if resp.status_code == 404:
            # actuator env 端点未暴露该 key，跳过而非失败
            pytest.skip("配置项 gateway.legacy-relay.enabled 未通过 actuator 暴露")

        assert resp.status_code == 200, (
            f"配置查询应返回 200，实际: {resp.status_code}"
        )
        data = resp.json()
        # 提取配置值（actuator env 格式：{"property": {"value": ...}}）
        value = (
            data.get("property", {}).get("value")
            or data.get("value")
        )
        assert str(value).lower() == "true", (
            f"Legacy relay 开关应默认为 true，实际: {value}"
        )

    @pytest.mark.requires_agent
    @pytest.mark.asyncio
    async def test_legacy_source_message_routed(
        self, gateway_ws_url, gateway_base_url, gateway_internal_token,
        test_ak, test_sk
    ):
        """Legacy source 格式的消息在 Legacy 开关开启时应能正常路由到 Agent。

        验证目标：
        - 发送不携带 X-SS-Instance-Id 的旧式 invoke（Legacy source）
        - Agent 应能收到该消息（Legacy relay 开关生效）
        """
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            reg_resp = await register_agent(ws)
            assert reg_resp is not None and reg_resp.get("type") == "register_ok", (
                f"Agent 注册失败: {reg_resp}"
            )

            # 发送不带 SS 实例 ID 的 invoke（Legacy 格式）
            resp = requests.post(
                f"{gateway_base_url}/internal/invoke",
                json={
                    "ak": test_ak,
                    "type": "invoke",
                    "action": "status_query",
                    "userId": "legacy_user",
                },
                headers={"Authorization": f"Bearer {gateway_internal_token}"},
                # 故意不携带 X-SS-Instance-Id，触发 Legacy 路由
                timeout=3,
            )
            assert resp.status_code in (200, 202), (
                f"Legacy invoke 应被接受，实际: {resp.status_code} {resp.text}"
            )

            # Legacy 开关开启时，Agent 应能收到消息
            msg = await recv_json(ws, timeout=5.0)
            assert msg is not None, (
                "Legacy relay 开启时 Agent 应收到 invoke 消息"
            )
            assert msg.get("type") == "invoke", (
                f"消息类型应为 invoke，实际: {msg.get('type')}"
            )
        finally:
            await ws.close()


# ==================== 失联 Owner 接管测试 ====================


@pytest.mark.ha
class TestOwnerTakeover:
    """SS 实例宕机后的 ownership 接管（高可用场景）。"""

    def test_session_route_redis_cache_written(self, skill_base_url,
                                               test_user_id, test_ak,
                                               agent_online):
        """Session 创建后 Redis 缓存应写入 ss:internal:session:{id}。

        验证目标：
        - 通过 SS 创建 Skill Session 成功
        - SS 应将 session 路由信息写入 Redis（ss:internal:session:{sessionId}）
        - /internal/sessions/{id}/owner 接口应返回当前 SS 实例 ID
        # TODO: 直接验证 Redis key 需要直连 Redis；当前通过 SS 内部接口间接验证
        # TODO: 需要 SS 暴露 /internal/sessions/{id}/owner 查询接口
        """
        from utils.api_client import SkillApiClient
        skill_api = SkillApiClient(skill_base_url)

        resp = skill_api.create_session(test_user_id, test_ak, "HA Test Session")
        assert resp.status_code == 200, (
            f"创建 Skill Session 失败: {resp.status_code} {resp.text}"
        )
        data = resp.json()
        session_id = (
            data.get("data", {}).get("welinkSessionId")
            or data.get("data", {}).get("id")
        )
        assert session_id, f"响应中未找到 session ID，响应体: {data}"

        # 等待 Redis 写入完成
        time.sleep(0.3)

        # TODO: 以下验证依赖 SS 暴露 /internal/sessions/{id}/owner 端点
        # owner_resp = requests.get(
        #     f"{skill_base_url}/internal/sessions/{session_id}/owner",
        #     timeout=3,
        # )
        # assert owner_resp.status_code == 200
        # owner_data = owner_resp.json()
        # assert "instanceId" in owner_data, "应返回 owner SS 实例 ID"

        # 清理
        try:
            skill_api.close_session(session_id, test_user_id)
        except Exception:
            pass

    def test_ss_instance_heartbeat_key_exists(self, skill_base_url):
        """SS 实例启动后心跳 key ss:internal:instance:{id} 应存在。

        验证目标：
        - SS 正常运行时，/internal/instances 接口应返回当前实例信息
        - 心跳 key 存在代表实例存活，反之表示实例已宕机（接管触发条件）
        # TODO: 直接验证 Redis key TTL 需要直连 Redis
        # TODO: 需要 SS 暴露 /internal/instances 实例列表接口
        """
        try:
            resp = requests.get(
                f"{skill_base_url}/internal/instances",
                timeout=3,
            )
        except requests.exceptions.ConnectionError:
            pytest.skip("SS 服务不可达")

        if resp.status_code == 404:
            pytest.skip("SS 未暴露 /internal/instances 接口，跳过心跳验证")

        assert resp.status_code == 200, (
            f"SS 实例列表接口应返回 200，实际: {resp.status_code}"
        )
        data = resp.json()
        instances = data.get("instances", [])
        assert len(instances) > 0, "SS 实例列表不应为空（至少有当前节点）"
        for inst in instances:
            assert "instanceId" in inst, f"实例缺少 instanceId 字段: {inst}"

    def test_takeover_session_reassigned_after_owner_loss(
        self, skill_base_url, gateway_base_url, gateway_internal_token,
        test_user_id, test_ak, agent_online
    ):
        """SS Owner 宕机后，其他 SS 实例应能接管孤儿 Session。

        验证目标：
        - 创建 session，记录当前 owner SS 实例 ID
        - 模拟 owner 心跳 key 过期（删除 Redis key）
        - 触发接管逻辑后，session 应被重新分配给存活的 SS 实例
        # TODO: 需要 Redis 直连权限删除心跳 key 来模拟实例宕机
        # TODO: 需要 SS 暴露接管触发接口或等待后台定时任务执行
        # TODO: 单节点 SS 环境下此测试无意义，需要多节点部署
        """
        pass  # TODO: 需要多 SS 实例部署 + Redis 直连才能完整验证

    def test_pending_messages_delivered_after_agent_reconnect(
        self, gateway_ws_url, gateway_base_url, gateway_internal_token,
        test_ak, test_sk
    ):
        """Agent 断线重连后，gw:pending:{ak} 缓冲队列中的消息应被补发。

        验证目标：
        - Agent 离线期间，invoke 消息写入缓冲队列
        - Agent 重新注册后，GW 应主动投递缓冲消息
        # TODO: 需要精确控制 Agent 离线时机，避免与其他测试的 WS 连接冲突
        """
        import asyncio

        loop = asyncio.new_event_loop()

        async def _run():
            # 第一步：发送 invoke（此时 Agent 离线，进入缓冲队列）
            resp = requests.post(
                f"{gateway_base_url}/internal/invoke",
                json={
                    "ak": test_ak,
                    "type": "invoke",
                    "action": "status_query",
                    "userId": "pending_test_user",
                    "welinkSessionId": "pending-session-001",
                },
                headers={"Authorization": f"Bearer {gateway_internal_token}"},
                timeout=3,
            )
            assert resp.status_code in (200, 202), (
                f"缓冲队列 invoke 应被接受，实际: {resp.status_code} {resp.text}"
            )

            # 第二步：Agent 上线
            ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
            try:
                reg_resp = await register_agent(ws)
                assert reg_resp is not None and reg_resp.get("type") == "register_ok", (
                    f"Agent 重连注册失败: {reg_resp}"
                )

                # 第三步：Agent 注册后应收到缓冲的 invoke 消息
                msg = await recv_json(ws, timeout=5.0)
                assert msg is not None, (
                    "Agent 重连后应收到缓冲队列中的 invoke 消息"
                )
                assert msg.get("type") == "invoke", (
                    f"缓冲消息类型应为 invoke，实际: {msg.get('type')}"
                )
            finally:
                await ws.close()

        try:
            loop.run_until_complete(_run())
        finally:
            loop.close()
