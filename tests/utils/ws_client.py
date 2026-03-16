"""
WebSocket 客户端封装。

提供 Agent WebSocket 和 Skill Stream WebSocket 的连接、收发消息工具。
"""

import asyncio
import json
from typing import Optional

import websockets
from websockets.client import WebSocketClientProtocol

from .auth import build_auth_subprotocol, build_internal_auth_subprotocol


async def connect_agent_ws(
    ws_url: str,
    ak: str,
    sk: str,
    timestamp: str = None,
    nonce: str = None,
    timeout: float = 5.0,
) -> WebSocketClientProtocol:
    """
    建立 Agent WebSocket 连接（含 AK/SK 签名握手）。

    返回打开的 WebSocket 连接，调用者负责关闭。
    """
    subprotocol = build_auth_subprotocol(ak, sk, timestamp, nonce)
    ws = await asyncio.wait_for(
        websockets.connect(
            ws_url,
            subprotocols=[subprotocol],
            open_timeout=timeout,
        ),
        timeout=timeout,
    )
    return ws


async def connect_agent_ws_expect_failure(
    ws_url: str,
    ak: str,
    sk: str,
    timestamp: str = None,
    nonce: str = None,
    timeout: float = 5.0,
) -> bool:
    """
    尝试建立 Agent WebSocket 连接，预期失败。

    返回 True 表示连接确实被拒绝。
    """
    subprotocol = build_auth_subprotocol(ak, sk, timestamp, nonce)
    try:
        ws = await asyncio.wait_for(
            websockets.connect(
                ws_url,
                subprotocols=[subprotocol],
                open_timeout=timeout,
            ),
            timeout=timeout,
        )
        # 连接意外成功，关闭它
        await ws.close()
        return False
    except (websockets.exceptions.InvalidStatusCode,
            websockets.exceptions.InvalidHandshake,
            ConnectionRefusedError,
            asyncio.TimeoutError,
            Exception):
        return True


async def connect_skill_stream_ws(
    ws_url: str,
    user_id: str,
    timeout: float = 5.0,
) -> WebSocketClientProtocol:
    """
    建立 Skill Stream WebSocket 连接（Cookie 认证）。
    """
    headers = {"Cookie": f"userId={user_id}"}
    try:
        # websockets >= 14.x uses additional_headers
        ws = await asyncio.wait_for(
            websockets.connect(
                ws_url,
                additional_headers=headers,
                open_timeout=timeout,
            ),
            timeout=timeout,
        )
    except TypeError:
        # websockets < 14.x uses extra_headers
        ws = await asyncio.wait_for(
            websockets.connect(
                ws_url,
                extra_headers=headers,
                open_timeout=timeout,
            ),
            timeout=timeout,
        )
    return ws


async def send_json(ws: WebSocketClientProtocol, data: dict) -> None:
    """发送 JSON 消息。"""
    await ws.send(json.dumps(data))


async def recv_json(ws: WebSocketClientProtocol, timeout: float = 5.0) -> Optional[dict]:
    """接收 JSON 消息，超时返回 None。"""
    try:
        raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
        return json.loads(raw)
    except asyncio.TimeoutError:
        return None
    except Exception:
        return None


async def recv_json_all(ws: WebSocketClientProtocol, timeout: float = 3.0,
                        max_messages: int = 50) -> list[dict]:
    """接收所有可用消息直到超时。"""
    messages = []
    for _ in range(max_messages):
        msg = await recv_json(ws, timeout=timeout)
        if msg is None:
            break
        messages.append(msg)
    return messages


async def register_agent(ws: WebSocketClientProtocol,
                         device_name: str = "TestPC",
                         mac_address: str = "AA:BB:CC:DD:EE:FF",
                         os_name: str = "Windows",
                         tool_type: str = "OPENCODE",
                         tool_version: str = "1.0") -> dict:
    """发送 register 消息并等待响应。"""
    await send_json(ws, {
        "type": "register",
        "deviceName": device_name,
        "macAddress": mac_address,
        "os": os_name,
        "toolType": tool_type,
        "toolVersion": tool_version,
    })
    return await recv_json(ws, timeout=5.0)


async def send_heartbeat(ws: WebSocketClientProtocol) -> None:
    """发送心跳消息。"""
    await send_json(ws, {"type": "heartbeat"})
