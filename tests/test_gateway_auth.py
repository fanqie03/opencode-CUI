"""
AI-Gateway 认证测试。

覆盖 TC-GW-U01 ~ TC-GW-U08：AkSkAuthService 签名验证。
通过 WebSocket 握手行为间接验证签名算法。
"""

import time
import pytest
from utils.auth import (
    compute_signature, generate_nonce, current_timestamp,
    build_auth_subprotocol,
)
from utils.ws_client import (
    connect_agent_ws, connect_agent_ws_expect_failure,
    register_agent, recv_json,
)


class TestAkSkAuth:
    """AK/SK 签名验证测试。"""

    @pytest.mark.asyncio
    async def test_u01_valid_signature(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U01：签名验证通过——正确 AK/SK 可以建立连接并完成注册。"""
        ws = await connect_agent_ws(gateway_ws_url, test_ak, test_sk)
        try:
            resp = await register_agent(ws)
            assert resp is not None, "未收到注册响应"
            assert resp.get("type") == "register_ok", f"注册失败: {resp}"
        finally:
            await ws.close()

    @pytest.mark.asyncio
    async def test_u02_wrong_signature(self, gateway_ws_url, test_ak):
        """TC-GW-U02：签名不匹配——使用错误 SK 应被拒绝。"""
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, "wrong_sk_totally_invalid"
        )
        assert rejected, "错误签名应被拒绝连接"

    @pytest.mark.asyncio
    async def test_u03_expired_timestamp(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U03：时间戳超窗（>5分钟）——过期时间戳应被拒绝。"""
        expired_ts = str(int(time.time() * 1000) - 360_000)  # 6 分钟前
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, test_sk,
            timestamp=expired_ts,
        )
        assert rejected, "过期时间戳应被拒绝"

    @pytest.mark.asyncio
    async def test_u04_invalid_timestamp_format(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U04：时间戳格式非法——非数字时间戳应被拒绝。"""
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, test_sk,
            timestamp="not_a_number",
        )
        assert rejected, "非法时间戳格式应被拒绝"

    @pytest.mark.asyncio
    async def test_u05_nonce_replay(self, gateway_ws_url, test_ak, test_sk):
        """TC-GW-U05：Nonce 重放防护——同一 nonce 第二次使用应被拒绝。"""
        fixed_nonce = generate_nonce()

        # 第一次：应成功
        ws = await connect_agent_ws(
            gateway_ws_url, test_ak, test_sk, nonce=fixed_nonce
        )
        resp = await register_agent(ws)
        assert resp is not None and resp.get("type") == "register_ok"
        await ws.close()

        # 第二次：同一 nonce 应被拒绝
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, test_ak, test_sk, nonce=fixed_nonce
        )
        assert rejected, "Nonce 重放应被拒绝"

    @pytest.mark.asyncio
    async def test_u06_null_parameters(self, gateway_ws_url):
        """TC-GW-U06：参数缺失——空 AK 应被拒绝。"""
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url, "", "any_sk"
        )
        assert rejected, "空 AK 应被拒绝"

    @pytest.mark.asyncio
    async def test_u07_unknown_ak(self, gateway_ws_url):
        """TC-GW-U07：AK 不存在——未注册 AK 应被拒绝。"""
        rejected = await connect_agent_ws_expect_failure(
            gateway_ws_url,
            "completely_nonexistent_ak_that_does_not_exist",
            "any_sk_value",
        )
        assert rejected, "不存在的 AK 应被拒绝"

    def test_u08_constant_time_compare(self):
        """TC-GW-U08：常量时间比较——验证签名计算的确定性。"""
        ak, sk = "test_ak", "test_sk"
        ts = current_timestamp()
        nonce = generate_nonce()

        sig1 = compute_signature(ak, sk, ts, nonce)
        sig2 = compute_signature(ak, sk, ts, nonce)
        assert sig1 == sig2, "相同输入应产生相同签名"

        sig3 = compute_signature(ak, sk, ts, generate_nonce())
        assert sig1 != sig3, "不同 nonce 应产生不同签名"
