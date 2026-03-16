"""
AK/SK HMAC-SHA256 签名工具。

模拟 PC Agent 使用的认证签名算法，用于 WebSocket 握手测试。
签名算法：HMAC-SHA256(SK, AK + timestamp + nonce) → Base64
"""

import base64
import hashlib
import hmac
import json
import time
import uuid


def generate_nonce() -> str:
    """生成唯一 nonce。"""
    return uuid.uuid4().hex


def current_timestamp() -> str:
    """当前秒级时间戳字符串（与 Java Instant.now().getEpochSecond() 一致）。"""
    return str(int(time.time()))


def compute_signature(ak: str, sk: str, timestamp: str, nonce: str) -> str:
    """
    计算 HMAC-SHA256 签名。

    签名算法与 AkSkAuthService.java 一致：
    sign = Base64(HMAC-SHA256(SK, AK + timestamp + nonce))
    """
    # Java: computeHmacSha256(SK, "{AK}{timestamp}{nonce}")
    message = (ak + timestamp + nonce).encode("utf-8")
    key = sk.encode("utf-8")
    mac = hmac.new(key, message, hashlib.sha256)
    # Java uses Base64.getEncoder() (standard Base64, not URL-safe)
    return base64.b64encode(mac.digest()).decode("utf-8")


def build_auth_subprotocol(ak: str, sk: str, timestamp: str = None,
                           nonce: str = None) -> str:
    """
    构建 WebSocket Sec-WebSocket-Protocol 认证子协议字符串。

    格式：auth.{Base64URL(JSON({ak, timestamp, nonce, sign}))}
    """
    if timestamp is None:
        timestamp = current_timestamp()
    if nonce is None:
        nonce = generate_nonce()

    signature = compute_signature(ak, sk, timestamp, nonce)

    # Java reads: authNode.path("ak"), authNode.path("ts"),
    #             authNode.path("nonce"), authNode.path("sign")
    payload = {
        "ak": ak,
        "ts": timestamp,
        "nonce": nonce,
        "sign": signature
    }
    json_bytes = json.dumps(payload).encode("utf-8")
    encoded = base64.urlsafe_b64encode(json_bytes).decode("utf-8").rstrip("=")
    return f"auth.{encoded}"


def build_internal_auth_subprotocol(token: str, source: str = "skill-server") -> str:
    """
    构建 Skill Server 内部 WebSocket 认证子协议。

    格式：auth.{Base64URL(JSON({token, source}))}
    """
    payload = {"token": token, "source": source}
    json_bytes = json.dumps(payload).encode("utf-8")
    encoded = base64.urlsafe_b64encode(json_bytes).decode("utf-8").rstrip("=")
    return f"auth.{encoded}"
