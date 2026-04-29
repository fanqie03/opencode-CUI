from __future__ import annotations

import asyncio
import json
import math
import random
import time
from typing import Any
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sse_starlette.sse import EventSourceResponse

app = FastAPI(
    title="Outbound Protocol Mock Server",
    version="1.0.0",
    description="Mock implementations for Standard, Dify, AgentMaker, Uniknow, and Athena protocols.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

ATHENA_TASKS: dict[str, dict[str, Any]] = {}

FIXED_TEXT = "你好，我是协议 Mock 服务。这是一条固定示例回复。"
REALISTIC_TEXT = "你好，我是协议 Mock 服务。我正在按更接近真实系统的方式流式返回结果。"
STANDARD_ASK_MORE = ["如何创建项目？", "支持哪些语言？"]
STANDARD_SEARCH_RESULTS = [
    {"index": "1", "title": "华为云介绍", "source": "官网"},
    {"index": "2", "title": "灵码功能说明", "source": "帮助文档"},
]
STANDARD_REFERENCES = [
    {
        "index": "1",
        "title": "API 文档",
        "source": "内部 Wiki",
        "url": "http://wiki.example.com/api",
        "content": "相关接口定义与字段说明。",
    }
]
AGENTMAKER_TOOL_RESULT = [
    {
        "result": json.dumps({"status": 200, "answer": "mock tool result"}, ensure_ascii=False),
        "Param": {
            "url": "https://example.com/mock-search",
            "headers": json.dumps({"x-mock": "true"}, ensure_ascii=False),
            "body": json.dumps({"query": "mock"}, ensure_ascii=False),
            "query": json.dumps({"page": 1}, ensure_ascii=False),
        },
        "toolType": "api_executor",
        "toolName": "MockSearch",
        "errorMessage": "",
    }
]


def unix_ts() -> int:
    return int(time.time())


def new_uuid() -> str:
    return str(uuid4())


def new_long_id() -> int:
    return random.randint(10**14, 10**15 - 1)


def normalize_mode(mode: str) -> str:
    normalized = mode.strip().lower()
    if normalized not in {"fixed", "realistic"}:
        raise HTTPException(status_code=400, detail="mode must be fixed or realistic")
    return normalized


def maybe_echo(prompt: str, fallback: str) -> str:
    cleaned = (prompt or "").strip()
    if cleaned.lower().startswith("debug"):
        return cleaned
    return fallback


def extract_prompt(payload: dict[str, Any]) -> str:
    for key in ("content", "query", "userInput", "input_text"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    input_value = payload.get("input")
    if isinstance(input_value, dict):
        for key in ("text", "content", "query"):
            value = input_value.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return ""


def split_text(text: str, realistic: bool) -> list[str]:
    if not text:
        return [""]
    if not realistic:
        midpoint = max(1, math.ceil(len(text) / 2))
        return [chunk for chunk in (text[:midpoint], text[midpoint:]) if chunk]

    chunks: list[str] = []
    cursor = 0
    sizes = [2, 3, 4, 5]
    size_index = 0
    while cursor < len(text):
        size = sizes[size_index % len(sizes)]
        chunks.append(text[cursor : cursor + size])
        cursor += size
        size_index += 1
    return chunks


def sse_json(data: dict[str, Any], event: str | None = None) -> dict[str, str]:
    payload = {"data": json.dumps(data, ensure_ascii=False)}
    if event:
        payload["event"] = event
    return payload


async def stream_sequence(
    events: list[dict[str, str]],
    *,
    realistic: bool,
    delay_ms: int = 120,
    ping_every: int = 3,
) -> Any:
    if realistic:
        yield {"event": "ping", "data": ""}

    for index, event in enumerate(events, start=1):
        if realistic:
            await asyncio.sleep(delay_ms / 1000)
            if ping_every > 0 and index > 1 and (index - 1) % ping_every == 0:
                yield {"event": "ping", "data": ""}
        yield event


def standard_frame(data: dict[str, Any], *, is_finish: bool) -> dict[str, Any]:
    return {
        "code": "0",
        "message": "",
        "error": "",
        "isFinish": is_finish,
        "data": data,
    }


def build_standard_events(prompt: str, mode: str) -> list[dict[str, str]]:
    realistic = mode == "realistic"
    answer = maybe_echo(prompt, REALISTIC_TEXT if realistic else FIXED_TEXT)
    chunks = split_text(answer, realistic=realistic)
    events = [
        sse_json(
            standard_frame(
                {"type": "planning", "planning": "正在分析用户意图" if realistic else "已命中固定示例场景"},
                is_finish=False,
            )
        ),
        sse_json(
            standard_frame(
                {
                    "type": "searching",
                    "searching": ["正在检索知识库", "正在联网搜索"] if realistic else ["跳过真实检索，直接返回固定结果"],
                },
                is_finish=False,
            )
        ),
        sse_json(
            standard_frame({"type": "searchResult", "searchResult": STANDARD_SEARCH_RESULTS}, is_finish=False)
        ),
        sse_json(
            standard_frame({"type": "reference", "references": STANDARD_REFERENCES}, is_finish=False)
        ),
    ]

    if realistic:
        events.append(
            sse_json(
                standard_frame({"type": "think", "content": "正在组织响应内容并分段输出。"}, is_finish=False)
            )
        )
        events.append(
            sse_json(standard_frame({"type": "askMore", "askMore": STANDARD_ASK_MORE}, is_finish=False))
        )

    for index, chunk in enumerate(chunks, start=1):
        events.append(
            sse_json(
                standard_frame({"type": "text", "content": chunk}, is_finish=index == len(chunks))
            )
        )
    return events


def make_usage(answer: str, *, latency: float) -> dict[str, Any]:
    prompt_tokens = 12
    completion_tokens = max(12, len(answer) // 2)
    total_tokens = prompt_tokens + completion_tokens
    return {
        "prompt_tokens": prompt_tokens,
        "prompt_unit_price": "0",
        "prompt_price_unit": "0.001",
        "prompt_price": "0",
        "completion_tokens": completion_tokens,
        "completion_unit_price": "0",
        "completion_price_unit": "0.001",
        "completion_price": "0",
        "total_tokens": total_tokens,
        "total_price": "0",
        "currency": "RMB",
        "latency": latency,
        "time_to_first_token": round(max(latency - 0.25, 0.01), 3),
        "time_to_generate": 0.25,
    }


def make_workflow_ids() -> dict[str, str]:
    return {
        "task_id": new_uuid(),
        "workflow_run_id": new_uuid(),
        "workflow_id": new_uuid(),
        "start_node_id": str(new_long_id()),
        "llm_node_id": str(new_long_id()),
        "end_node_id": "answer",
        "start_exec_id": new_uuid(),
        "llm_exec_id": new_uuid(),
        "end_exec_id": new_uuid(),
    }


def build_workflow_events(
    *,
    prompt: str,
    mode: str,
    user: str,
    include_conversation: bool,
    conversation_id: str | None,
    include_message_events: bool,
) -> list[dict[str, str]]:
    realistic = mode == "realistic"
    answer = maybe_echo(prompt, REALISTIC_TEXT if realistic else FIXED_TEXT)
    answer_chunks = split_text(answer, realistic=realistic)
    ids = make_workflow_ids()
    created_at = unix_ts()
    usage = make_usage(answer, latency=1.86 if realistic else 0.42)
    workflow_inputs = {
        "sys.files": [],
        "sys.user_id": user or "mock-user",
        "sys.app_id": new_uuid(),
        "sys.workflow_id": ids["workflow_id"],
        "sys.workflow_run_id": ids["workflow_run_id"],
        "sys.query": prompt,
        "sys.dialogue_count": 1,
    }
    if include_conversation and conversation_id:
        workflow_inputs["sys.conversation_id"] = conversation_id

    common: dict[str, Any] = {
        "task_id": ids["task_id"],
        "workflow_run_id": ids["workflow_run_id"],
    }
    if include_conversation and conversation_id:
        common["conversation_id"] = conversation_id
        common["message_id"] = ids["llm_exec_id"]
        common["created_at"] = created_at

    workflow_started = {
        **common,
        "event": "workflow_started",
        "data": {
            "id": ids["workflow_run_id"],
            "workflow_id": ids["workflow_id"],
            "inputs": workflow_inputs,
            "created_at": created_at,
            "reason": "initial",
        },
    }
    start_node = {
        **common,
        "event": "node_started",
        "data": {
            "id": ids["start_exec_id"],
            "node_id": ids["start_node_id"],
            "node_type": "start",
            "title": "用户输入",
            "index": 1,
            "predecessor_node_id": None,
            "inputs": None,
            "inputs_truncated": False,
            "created_at": created_at,
            "extras": {},
            "iteration_id": None,
            "loop_id": None,
            "agent_strategy": None,
        },
    }
    start_finished = {
        **common,
        "event": "node_finished",
        "data": {
            "id": ids["start_exec_id"],
            "node_id": ids["start_node_id"],
            "node_type": "start",
            "title": "用户输入",
            "index": 1,
            "predecessor_node_id": None,
            "inputs": workflow_inputs,
            "inputs_truncated": False,
            "process_data": {},
            "process_data_truncated": False,
            "outputs": workflow_inputs,
            "outputs_truncated": False,
            "status": "succeeded",
            "error": None,
            "elapsed_time": 0.0001,
            "execution_metadata": None,
            "created_at": created_at,
            "finished_at": created_at,
            "files": [],
            "iteration_id": None,
            "loop_id": None,
        },
    }
    llm_started = {
        **common,
        "event": "node_started",
        "data": {
            "id": ids["llm_exec_id"],
            "node_id": ids["llm_node_id"],
            "node_type": "llm",
            "title": "LLM",
            "index": 2,
            "predecessor_node_id": ids["start_node_id"],
            "inputs": None,
            "inputs_truncated": False,
            "created_at": created_at,
            "extras": {},
            "iteration_id": None,
            "loop_id": None,
            "agent_strategy": None,
        },
    }
    llm_finished = {
        **common,
        "event": "node_finished",
        "data": {
            "id": ids["llm_exec_id"],
            "node_id": ids["llm_node_id"],
            "node_type": "llm",
            "title": "LLM",
            "index": 2,
            "predecessor_node_id": ids["start_node_id"],
            "inputs": {},
            "inputs_truncated": False,
            "process_data": {
                "model_mode": "chat",
                "prompts": [
                    {"role": "system", "text": "你是一个智能机器人", "files": []},
                    {"role": "user", "text": prompt, "files": []},
                ],
                "usage": usage,
                "finish_reason": "stop",
                "model_provider": "mock/provider",
                "model_name": "mock-model",
            },
            "process_data_truncated": False,
            "outputs": {"text": answer, "reasoning_content": "", "usage": usage, "finish_reason": "stop"},
            "outputs_truncated": False,
            "status": "succeeded",
            "error": None,
            "elapsed_time": round(usage["latency"], 6),
            "execution_metadata": {
                "total_tokens": usage["total_tokens"],
                "total_price": usage["total_price"],
                "currency": usage["currency"],
            },
            "created_at": created_at,
            "finished_at": created_at + 1,
            "files": [],
            "iteration_id": None,
            "loop_id": None,
        },
    }
    end_started = {
        **common,
        "event": "node_started",
        "data": {
            "id": ids["end_exec_id"],
            "node_id": ids["end_node_id"],
            "node_type": "answer",
            "title": "直接回复",
            "index": 3,
            "predecessor_node_id": ids["llm_node_id"],
            "inputs": None,
            "inputs_truncated": False,
            "created_at": created_at + 1,
            "extras": {},
            "iteration_id": None,
            "loop_id": None,
            "agent_strategy": None,
        },
    }
    end_finished = {
        **common,
        "event": "node_finished",
        "data": {
            "id": ids["end_exec_id"],
            "node_id": ids["end_node_id"],
            "node_type": "answer",
            "title": "直接回复",
            "index": 3,
            "predecessor_node_id": ids["llm_node_id"],
            "inputs": {},
            "inputs_truncated": False,
            "process_data": {},
            "process_data_truncated": False,
            "outputs": {"answer": answer, "files": []},
            "outputs_truncated": False,
            "status": "succeeded",
            "error": None,
            "elapsed_time": 0.0001,
            "execution_metadata": None,
            "created_at": created_at + 1,
            "finished_at": created_at + 1,
            "files": [],
            "iteration_id": None,
            "loop_id": None,
        },
    }
    workflow_finished = {
        **common,
        "event": "workflow_finished",
        "data": {
            "id": ids["workflow_run_id"],
            "workflow_id": ids["workflow_id"],
            "status": "succeeded",
            "outputs": {"answer" if include_message_events else "a": answer, "files": []},
            "error": None,
            "elapsed_time": round(usage["latency"] + 0.4, 6),
            "total_tokens": usage["total_tokens"],
            "total_steps": 3,
            "created_by": {"id": new_uuid(), "user": user or "mock-user"},
            "created_at": created_at,
            "finished_at": created_at + 1,
            "exceptions_count": 0,
            "files": [],
        },
    }

    events = [
        sse_json(workflow_started, event="workflow_started"),
        sse_json(start_node, event="node_started"),
        sse_json(start_finished, event="node_finished"),
        sse_json(llm_started, event="node_started"),
    ]

    chunk_event_name = "message" if include_message_events else "text_chunk"
    for chunk in answer_chunks:
        if include_message_events:
            payload = {
                **common,
                "event": "message",
                "id": ids["llm_exec_id"],
                "answer": chunk,
                "from_variable_selector": [ids["llm_node_id"], "text"],
            }
        else:
            payload = {
                **common,
                "event": "text_chunk",
                "data": {"text": chunk, "from_variable_selector": [ids["llm_node_id"], "text"]},
            }
        events.append(sse_json(payload, event=chunk_event_name))

    events.extend(
        [
            sse_json(llm_finished, event="node_finished"),
            sse_json(end_started, event="node_started"),
            sse_json(end_finished, event="node_finished"),
        ]
    )
    if include_message_events:
        message_end = {
            **common,
            "event": "message_end",
            "id": ids["llm_exec_id"],
            "metadata": {
                "annotation_reply": None,
                "retriever_resources": [],
                "usage": usage,
            },
            "files": [],
        }
        events.append(sse_json(message_end, event="message_end"))
    events.append(sse_json(workflow_finished, event="workflow_finished"))
    return events


def build_agent_events(*, prompt: str, mode: str, conversation_id: str, user: str) -> list[dict[str, str]]:
    realistic = mode == "realistic"
    answer = maybe_echo(prompt, REALISTIC_TEXT if realistic else FIXED_TEXT)
    answer_chunks = split_text(answer, realistic=realistic)
    created_at = unix_ts()
    task_id = new_uuid()
    message_id = new_uuid()
    thought_id = new_uuid()
    usage = make_usage(answer, latency=2.06 if realistic else 0.38)

    thought_prefix = {
        "event": "agent_thought",
        "conversation_id": conversation_id,
        "message_id": message_id,
        "created_at": created_at,
        "task_id": task_id,
        "id": thought_id,
        "position": 1,
        "thought": "",
        "observation": "",
        "tool": "",
        "tool_labels": {},
        "tool_input": "",
        "message_files": [],
    }

    events = [sse_json(thought_prefix, event="agent_thought")]
    for chunk in answer_chunks:
        events.append(
            sse_json(
                {
                    "event": "agent_message",
                    "conversation_id": conversation_id,
                    "message_id": message_id,
                    "created_at": created_at,
                    "task_id": task_id,
                    "id": message_id,
                    "answer": chunk,
                },
                event="agent_message",
            )
        )
    events.append(sse_json({**thought_prefix, "thought": answer}, event="agent_thought"))
    events.append(
        sse_json(
            {
                "event": "message_end",
                "conversation_id": conversation_id,
                "message_id": message_id,
                "created_at": created_at,
                "task_id": task_id,
                "id": message_id,
                "metadata": {
                    "annotation_reply": None,
                    "retriever_resources": [],
                    "usage": usage,
                },
                "files": None,
            },
            event="message_end",
        )
    )
    return events


def agentmaker_frame(
    *,
    event_name: str,
    request_id: str,
    session_id: str,
    content: str,
    tool_result: list[dict[str, Any]] | None = None,
) -> dict[str, str]:
    return sse_json(
        {
            "errors": "",
            "meta": None,
            "data": {
                "id": "1",
                "type": "AgentDialogueVO",
                "attributes": {
                    "requestId": request_id,
                    "agentStatus": event_name,
                    "status": "",
                    "content": content,
                    "sessionId": session_id,
                    "toolResult": tool_result or [],
                },
            },
        },
        event=event_name,
    )


def build_agentmaker_events(*, prompt: str, mode: str, session_id: str) -> list[dict[str, str]]:
    realistic = mode == "realistic"
    answer = maybe_echo(prompt, REALISTIC_TEXT if realistic else FIXED_TEXT)
    chunks = split_text(answer, realistic=realistic)
    request_id = new_uuid()
    events = [
        agentmaker_frame(
            event_name="PROCESSING",
            request_id=request_id,
            session_id=session_id,
            content="正在分析请求" if realistic else "命中固定示例返回",
        )
    ]
    if realistic:
        events.append(
            agentmaker_frame(
                event_name="TOOL_EXEC",
                request_id=request_id,
                session_id=session_id,
                content="正在执行工具调用",
                tool_result=AGENTMAKER_TOOL_RESULT,
            )
        )
    for chunk in chunks:
        events.append(
            agentmaker_frame(
                event_name="ANSWER",
                request_id=request_id,
                session_id=session_id,
                content=chunk,
            )
        )
    events.append(
        agentmaker_frame(
            event_name="DONE",
            request_id=request_id,
            session_id=session_id,
            content=answer,
        )
    )
    return events


@app.get("/")
async def index() -> dict[str, Any]:
    return {
        "name": "outbound-protocol-mock-server",
        "docs": "/docs",
        "health": "/healthz",
        "routes": [
            "/mock/standard/stream",
            "/api/v1/chat-messages",
            "/v1/chat-messages",
            "/v1/workflows/run",
            "/mock/agentmaker/sse",
            "/mock/uniknow/chat",
            "/mock/athena/tasks",
            "/mock/athena/stream",
        ],
    }


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/mock/standard/stream")
async def standard_stream(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
) -> EventSourceResponse:
    payload = await request.json()
    normalized_mode = normalize_mode(mode)
    prompt = extract_prompt(payload)
    events = build_standard_events(prompt, normalized_mode)
    return EventSourceResponse(stream_sequence(events, realistic=normalized_mode == "realistic"))


async def dify_chat_messages(
    request: Request,
    *,
    mode: str,
    variant: str,
) -> EventSourceResponse:
    payload = await request.json()
    normalized_mode = normalize_mode(mode)
    normalized_variant = variant.strip().lower()
    prompt = extract_prompt(payload)
    user = payload.get("user", "mock-user")
    conversation_id = payload.get("conversation_id") or new_uuid()

    if normalized_variant == "agent":
        events = build_agent_events(
            prompt=prompt,
            mode=normalized_mode,
            conversation_id=conversation_id,
            user=user,
        )
    elif normalized_variant == "chatflow":
        events = build_workflow_events(
            prompt=prompt,
            mode=normalized_mode,
            user=user,
            include_conversation=True,
            conversation_id=conversation_id,
            include_message_events=True,
        )
    else:
        raise HTTPException(status_code=400, detail="variant must be agent or chatflow")

    return EventSourceResponse(stream_sequence(events, realistic=normalized_mode == "realistic"))


@app.post("/api/v1/chat-messages")
async def lingque_chat_messages(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
    variant: str = Query("agent", description="agent or chatflow"),
) -> EventSourceResponse:
    return await dify_chat_messages(request, mode=mode, variant=variant)


@app.post("/v1/chat-messages")
async def baize_chat_messages(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
    variant: str = Query("agent", description="agent or chatflow"),
) -> EventSourceResponse:
    return await dify_chat_messages(request, mode=mode, variant=variant)


@app.post("/v1/workflows/run")
async def workflow_run(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
) -> EventSourceResponse:
    payload = await request.json()
    normalized_mode = normalize_mode(mode)
    prompt = extract_prompt(payload)
    user = payload.get("user", "mock-user")
    events = build_workflow_events(
        prompt=prompt,
        mode=normalized_mode,
        user=user,
        include_conversation=False,
        conversation_id=None,
        include_message_events=False,
    )
    return EventSourceResponse(stream_sequence(events, realistic=normalized_mode == "realistic"))


@app.post("/mock/agentmaker/sse")
async def agentmaker_sse(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
) -> EventSourceResponse:
    payload = await request.json()
    normalized_mode = normalize_mode(mode)
    session_id = payload.get("sessionId") or new_uuid()
    prompt = extract_prompt(payload)
    events = build_agentmaker_events(prompt=prompt, mode=normalized_mode, session_id=session_id)
    return EventSourceResponse(stream_sequence(events, realistic=normalized_mode == "realistic"))


@app.post("/mock/uniknow/chat")
async def uniknow_chat(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
) -> JSONResponse:
    payload = await request.json()
    normalized_mode = normalize_mode(mode)
    prompt = extract_prompt(payload)
    answer = maybe_echo(prompt, REALISTIC_TEXT if normalized_mode == "realistic" else FIXED_TEXT)
    return JSONResponse(
        {
            "data": [
                {
                    "taskInfo": {
                        "slots": {
                            "result": {
                                "data": answer,
                                "requestId": new_uuid(),
                            }
                        }
                    }
                }
            ]
        }
    )


@app.post("/mock/athena/tasks")
async def athena_create_task(
    request: Request,
    mode: str = Query("fixed", description="fixed or realistic"),
) -> JSONResponse:
    payload = await request.json()
    normalized_mode = normalize_mode(mode)
    task_id = new_long_id()
    ATHENA_TASKS[str(task_id)] = {
        "prompt": extract_prompt(payload),
        "mode": normalized_mode,
        "sessionId": payload.get("sessionId") or new_uuid(),
        "created_at": unix_ts(),
    }
    return JSONResponse({"data": task_id})


@app.get("/mock/athena/stream")
async def athena_stream(id: str = Query(..., description="Athena SSE task id")) -> EventSourceResponse:
    task = ATHENA_TASKS.get(id)
    if not task:
        raise HTTPException(status_code=404, detail="athena task not found")
    events = build_standard_events(task["prompt"], task["mode"])
    return EventSourceResponse(stream_sequence(events, realistic=task["mode"] == "realistic"))
