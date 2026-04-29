
## 标准协议

### 入参header

#- athena表示为该appId的token
1.SOA token, apig token - athena
    Authorization = soa_token
    x-hw-id = xxx
    x-appkey = xxx

2. IAM token - athena
    Authorization = iam_token
3. 集成账号token - athena
    Authorization = 集成账号token
4. 自定义token
    自定义key = 自定义value
5. cookie(附加)
    cookie: 个人cookie

### 入参
```jsonc
{
    "type":"text",  // IMAGE-V1 图片, text文本, WELINK-CARD-ACTION, filesCard 文件卡片
    "content":"发送内容",
    "sendUserAccount":"发送人账号",
    "imGroupId":"", // 可选，非必传
    "clientLang":"zh", // 客户端语言(zh, en)
    "clientType":"asst-pc", // 客户端类型(asst-pc pc端, asst-wecode 移动端, 未知null)
    "topicId":123, // long 会话主题id, 某次会话聚合的主题id
    "messageId":123, // long 消息id，用于追踪每次对话
    "extParameters": {
        "isHwEmployee":true, // 是否是华为雇员
        "actionParam":"", // 机器人自定义参数
        "filesCard": [ // 类型为filesCard时必传，其他类型非必传必传
            {
                "id":"223344", // 文件id
                "order":1 // 文件顺序
            }
        ],
        "knowledgeId":["1122aa"] // 知识库id列表
    }
}
```

### 出参(流式)

#### 定义

```jsonc
{
    "code":"0",
    "message":"提示信息",
    "error":"异常信息",
    "isFinish": true, // 流式响应是否结束
    "data":{
        "type":"text", // planning 规划, searching 搜索中, searchResult 搜索结果, reference 引用, think深度思考, text 文本, askMore追问; text必选，其他可选
        "content":"响应文本内容", // 可以多次返回，type为text或think时，返回的内容
        "planning":"", // 可以多次返回，type为planning时，返回的内容
        "searching":[""], // 一次性返回，可以返回空数组，type为searching时，返回的内容
        "searchResult": [ // 一次性返回，可以返回空数组，type为searReuslt时，返回的内容
            {
                "index":"1", // 序号
                "title":"标题",
                "source":"来源"
            }
        ],
        "references":[ // 一次性返回，可以返回空数组，type为refenrence时，返回的内容
            {
                "index":"1", // 序号
                "title":"标题",
                "source":"来源",
                "url":"链接, http或https",
                "content":"内容"
            }
        ],
        "askMore":["a","x"] // type为askMore时返回的内容，一次性返回
    }
}
```

#### 返回示例

```
data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"planning", "planning":"正在分析用户意图"}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"planning", "planning":"正在拆解任务步骤"}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"searching", "searching":["正在检索知识库", "正在联网搜索"]}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"searchResult", "searchResult": [{"index":"1", "title":"华为云介绍", "source":"官网"}, {"index":"2", "title":"灵码功能说明", "source":"帮助文档"}]}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"think", "content":"首先需要考虑..."}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"think", "content":"其次需要验证..."}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"reference", "references": [{"index":"1", "title":"API文档", "source":"内部Wiki", "url":"http://wiki.example.com/api", "content":"相关接口定义..."}]}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"text", "content":"您好，"}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"text", "content":"我是智能助手。"}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"text", "content":"请问有什么可以帮您？"}}

data: {"code":"0", "message":"", "error":"", "isFinish":false, "data":{"type":"askMore", "askMore":["如何创建项目？", "支持哪些语言？"]}}
```

## 灵雀/白泽(dify) (sse请求

> dify 协议 https://docs.dify.ai/api-reference/%E5%AF%B9%E8%AF%9D%E6%B6%88%E6%81%AF/%E5%8F%91%E9%80%81%E5%AF%B9%E8%AF%9D%E6%B6%88%E6%81%AF

dify有3种返回模式：chatflow/agent/workflow

### 请求地址

灵雀：
/api/v1/chat-messages

白泽：
chatflow和agent: /v1/chat-messages
workflow:/v1/workflows/run

### 入参header

灵雀：
#- S008026表示为该appId的SOA token
Authorization: SOA token - S008026
x-app-id: 灵雀(dify)平台下的机器人id

白泽：(每个机器人都可以获取一个固定的秘钥)
Authorization: Bearer {api_key}

### 入参body

```jsonc
{
    "query":"你好",
    "input":{"text", "你好"}, // 额外参数
    "response_mode":"streaming", // 流式响应
    "conversation_id":"", //（选填）会话 ID，需要基于之前的聊天记录继续对话，必须传之前消息的 conversation_id。
    "user":"c1234"  // 外部系统标准的用户id
}
```

### 出参body


#### workflow

##### 定义

```js
流式块中根据 event 不同，结构也不同，包含以下类型：

event: workflow_started workflow 开始执行
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 workflow_started
    data (object) 详细内容
        id (string) workflow 执行 ID
        workflow_id (string) 关联 Workflow ID
        created_at (timestamp) 开始时间
event: node_started node 开始执行
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 node_started
    data (object) 详细内容
        id (string) workflow 执行 ID
        node_id (string) 节点 ID
        node_type (string) 节点类型
        title (string) 节点名称
        index (int) 执行序号，用于展示 Tracing Node 顺序
        predecessor_node_id (string) 前置节点 ID，用于画布展示执行路径
        inputs (object) 节点中所有使用到的前置节点变量内容
        created_at (timestamp) 开始时间
event: text_chunk 文本片段
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 text_chunk
    data (object) 详细内容
        text (string) 文本内容
        from_variable_selector (array) 文本来源路径，帮助开发者了解文本是由哪个节点的哪个变量生成的
event: node_finished node 执行结束，成功失败同一事件中不同状态
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 node_finished
    data (object) 详细内容
    id (string) node 执行 ID
        node_id (string) 节点 ID
        index (int) 执行序号，用于展示 Tracing Node 顺序
        predecessor_node_id (string) optional 前置节点 ID，用于画布展示执行路径
        inputs (object) 节点中所有使用到的前置节点变量内容
        process_data (json) Optional 节点过程数据
        outputs (json) Optional 输出内容
        status (string) 执行状态 running / succeeded / failed / stopped
        error (string) Optional 错误原因
        elapsed_time (float) Optional 耗时(s)
        execution_metadata (json) 元数据
            total_tokens (int) optional 总使用 tokens
            total_price (decimal) optional 总费用
            currency (string) optional 货币，如 USD / RMB
        created_at (timestamp) 开始时间
event: workflow_finished workflow 执行结束，成功失败同一事件中不同状态
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 workflow_finished
    data (object) 详细内容
        id (string) workflow 执行 ID
        workflow_id (string) 关联 Workflow ID
        status (string) 执行状态 running / succeeded / failed / stopped
        outputs (json) Optional 输出内容
        error (string) Optional 错误原因
        elapsed_time (float) Optional 耗时(s)
        total_tokens (int) Optional 总使用 tokens
        total_steps (int) 总步数（冗余），默认 0
        created_at (timestamp) 开始时间
        finished_at (timestamp) 结束时间
event: tts_message TTS 音频流事件，即：语音合成输出。内容是Mp3格式的音频块，使用 base64 编码后的字符串，播放的时候直接解码即可。(开启自动播放才有此消息)
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    audio (string) 语音合成之后的音频块使用 Base64 编码之后的文本内容，播放的时候直接 base64 解码送入播放器即可
    created_at (int) 创建时间戳，如：1705395332
event: tts_message_end TTS 音频流结束事件，收到这个事件表示音频流返回结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    audio (string) 结束事件是没有音频的，所以这里是空字符串
    created_at (int) 创建时间戳，如：1705395332
event: ping 每 10s 一次的 ping 事件，保持连接存活。
```

##### 示例

```js
event: ping

data: {"event":"workflow_started","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"7351221f-20b6-4dce-afe4-00f08518c2a9","workflow_id":"61de840d-fb67-4471-be35-8f79ebc548ea","inputs":{"text":"你好","sys.files":[],"sys.user_id":"abc-123","sys.app_id":"f9031b0c-ea64-432d-8783-925e3b829d0f","sys.workflow_id":"61de840d-fb67-4471-be35-8f79ebc548ea","sys.workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9"},"created_at":1775994372,"reason":"initial"}}

data: {"event":"node_started","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"2fb73763-0190-4576-98d4-b957dc2bad22","node_id":"1775628337233","node_type":"start","title":"用户输入","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775994372,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"2fb73763-0190-4576-98d4-b957dc2bad22","node_id":"1775628337233","node_type":"start","title":"用户输入","index":1,"predecessor_node_id":null,"inputs":{"text":"你好","sys.files":[],"sys.user_id":"abc-123","sys.app_id":"f9031b0c-ea64-432d-8783-925e3b829d0f","sys.workflow_id":"61de840d-fb67-4471-be35-8f79ebc548ea","sys.workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","sys.timestamp":1775994371},"inputs_truncated":false,"process_data":{},"process_data_truncated":false,"outputs":{"text":"你好","sys.files":[],"sys.user_id":"abc-123","sys.app_id":"f9031b0c-ea64-432d-8783-925e3b829d0f","sys.workflow_id":"61de840d-fb67-4471-be35-8f79ebc548ea","sys.workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","sys.timestamp":1775994371},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.000271,"execution_metadata":null,"created_at":1775994372,"finished_at":1775994372,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"73c2299e-d215-4cfe-a483-922e74f6504a","node_id":"1775994246420","node_type":"http-request","title":"HTTP 请求","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775994372,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"73c2299e-d215-4cfe-a483-922e74f6504a","node_id":"1775994246420","node_type":"http-request","title":"HTTP 请求","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{"request":"GET /json HTTP/1.1\r\nHost: httpbin.org\r\n\r\n"},"process_data_truncated":false,"outputs":{"status_code":200,"body":"{\n  \"slideshow\": {\n    \"author\": \"Yours Truly\", \n    \"date\": \"date of publication\", \n    \"slides\": [\n      {\n        \"title\": \"Wake up to WonderWidgets!\", \n        \"type\": \"all\"\n      }, \n      {\n        \"items\": [\n          \"Why <em>WonderWidgets</em> are great\", \n          \"Who <em>buys</em> WonderWidgets\"\n        ], \n        \"title\": \"Overview\", \n        \"type\": \"all\"\n      }\n    ], \n    \"title\": \"Sample Slide Show\"\n  }\n}\n","headers":{"date":"Sun, 12 Apr 2026 11:46:12 GMT","content-type":"application/json","content-length":"429","connection":"keep-alive","server":"gunicorn/19.9.0","access-control-allow-origin":"*","access-control-allow-credentials":"true"},"files":[]},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.027603,"execution_metadata":null,"created_at":1775994372,"finished_at":1775994372,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"b00de244-5527-4010-b470-6ab675081f9f","node_id":"1775628341312","node_type":"llm","title":"LLM","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775994372,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"你好","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"👋！","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"有什么","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"可以帮助","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"你的","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"吗","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"？","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"","from_variable_selector":["1775628341312","text"]}}

data: {"event":"text_chunk","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"text":"","from_variable_selector":["1775628341312","text"]}}

data: {"event":"node_finished","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"b00de244-5527-4010-b470-6ab675081f9f","node_id":"1775628341312","node_type":"llm","title":"LLM","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{"model_mode":"chat","prompts":[{"role":"system","text":"你是一个智能机器人","files":[]},{"role":"user","text":"你好","files":[]}],"usage":{"prompt_tokens":12,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":12,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":24,"total_price":"0","currency":"RMB","latency":1.801,"time_to_first_token":1.492,"time_to_generate":0.309},"finish_reason":"stop","model_provider":"langgenius/zhipuai/zhipuai","model_name":"glm-4-flash"},"process_data_truncated":false,"outputs":{"text":"你好👋！有什么可以帮助你的吗？","reasoning_content":"","usage":{"prompt_tokens":12,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":12,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":24,"total_price":"0","currency":"RMB","latency":1.801,"time_to_first_token":1.492,"time_to_generate":0.309},"finish_reason":"stop"},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":1.803476,"execution_metadata":{"total_tokens":24,"total_price":"0","currency":"RMB"},"created_at":1775994372,"finished_at":1775994374,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"71d600c1-07df-40de-8489-881e13b2e5ef","node_id":"1775628344842","node_type":"end","title":"输出","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775994374,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"71d600c1-07df-40de-8489-881e13b2e5ef","node_id":"1775628344842","node_type":"end","title":"输出","index":1,"predecessor_node_id":null,"inputs":{"a":"你好👋！有什么可以帮助你的吗？"},"inputs_truncated":false,"process_data":{},"process_data_truncated":false,"outputs":{"a":"你好👋！有什么可以帮助你的吗？"},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.000069,"execution_metadata":null,"created_at":1775994374,"finished_at":1775994374,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"workflow_finished","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"445aefc4-ff55-4d7e-8757-300b8bfadd09","data":{"id":"7351221f-20b6-4dce-afe4-00f08518c2a9","workflow_id":"61de840d-fb67-4471-be35-8f79ebc548ea","status":"succeeded","outputs":{"a":"你好👋！有什么可以帮助你的吗？"},"error":null,"elapsed_time":1.979352,"total_tokens":24,"total_steps":4,"created_by":{"id":"0bf3e73f-7bb7-4d32-9062-10776beb7e67","user":"abc-123"},"created_at":1775994372,"finished_at":1775994374,"exceptions_count":0,"files":[]}}


```

#### agent

##### 定义

```js
流式块中根据 event 不同，结构也不同：

event: message LLM 返回文本块事件，即：完整的文本以分块的方式输出。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    answer (string) LLM 返回文本块内容
    created_at (int) 创建时间戳，如：1705395332
event: agent_message Agent模式下返回文本块事件，即：在Agent模式下，文章的文本以分块的方式输出（仅Agent模式下使用）
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    answer (string) LLM 返回文本块内容
    created_at (int) 创建时间戳，如：1705395332
event: agent_thought Agent模式下有关Agent思考步骤的相关内容，涉及到工具调用（仅Agent模式下使用）
    id (string) agent_thought ID，每一轮Agent迭代都会有一个唯一的id
    task_id (string) 任务ID，用于请求跟踪下方的停止响应接口
    message_id (string) 消息唯一ID
    position (int) agent_thought在消息中的位置，如第一轮迭代position为1
    thought (string) agent的思考内容
    observation (string) 工具调用的返回结果
    tool (string) 使用的工具列表，以 ; 分割多个工具
    tool_input (string) 工具的输入，JSON格式的字符串(object)。如：{"dalle3": {"prompt": "a cute cat"}}
    created_at (int) 创建时间戳，如：1705395332
    message_files (array[string]) 当前 agent_thought 关联的文件ID
        file_id (string) 文件ID
    conversation_id (string) 会话ID
event: message_file 文件事件，表示有新文件需要展示
    id (string) 文件唯一ID
    type (string) 文件类型，目前仅为image
    belongs_to (string) 文件归属，user或assistant，该接口返回仅为 assistant
    url (string) 文件访问地址
    conversation_id (string) 会话ID
event: message_end 消息结束事件，收到此事件则代表流式返回结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    metadata (object) 元数据
        usage (Usage) 模型用量信息
        retriever_resources (array[RetrieverResource]) 引用和归属分段列表
event: tts_message TTS 音频流事件，即：语音合成输出。内容是Mp3格式的音频块，使用 base64 编码后的字符串，播放的时候直接解码即可。(开启自动播放才有此消息)
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    audio (string) 语音合成之后的音频块使用 Base64 编码之后的文本内容，播放的时候直接 base64 解码送入播放器即可
    created_at (int) 创建时间戳，如：1705395332
event: tts_message_end TTS 音频流结束事件，收到这个事件表示音频流返回结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    audio (string) 结束事件是没有音频的，所以这里是空字符串
    created_at (int) 创建时间戳，如：1705395332
event: message_replace 消息内容替换事件。 开启内容审查和审查输出内容时，若命中了审查条件，则会通过此事件替换消息内容为预设回复。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    answer (string) 替换内容（直接替换 LLM 所有回复文本）
    created_at (int) 创建时间戳，如：1705395332
event: error 流式输出过程中出现的异常会以 stream event 形式输出，收到异常事件后即结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    status (int) HTTP 状态码
    code (string) 错误码
    message (string) 错误消息
event: ping 每 10s 一次的 ping 事件，保持连接存活。
```

##### 示例

```js
data: {"event":"agent_thought","conversation_id":"9ad8afe6-a00c-499c-a5f0-b2bfe5d74a49","message_id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","created_at":1775993023,"task_id":"3cd95f53-e1c4-45c3-8ef4-59f5d6def0dc","id":"98861ecb-38c7-478c-890f-33bffe2aaef2","position":1,"thought":"","observation":"","tool":"","tool_labels":{},"tool_input":"","message_files":[]}

data: {"event":"agent_message","conversation_id":"9ad8afe6-a00c-499c-a5f0-b2bfe5d74a49","message_id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","created_at":1775993023,"task_id":"3cd95f53-e1c4-45c3-8ef4-59f5d6def0dc","id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","answer":"你好！有什么可以帮助你的吗？"}

data: {"event":"agent_message","conversation_id":"9ad8afe6-a00c-499c-a5f0-b2bfe5d74a49","message_id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","created_at":1775993023,"task_id":"3cd95f53-e1c4-45c3-8ef4-59f5d6def0dc","id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","answer":"无论是写作还是纠错，我都会尽力协助你。请告诉我你的需求。"}

data: {"event":"agent_message","conversation_id":"9ad8afe6-a00c-499c-a5f0-b2bfe5d74a49","message_id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","created_at":1775993023,"task_id":"3cd95f53-e1c4-45c3-8ef4-59f5d6def0dc","id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","answer":""}

data: {"event":"agent_thought","conversation_id":"9ad8afe6-a00c-499c-a5f0-b2bfe5d74a49","message_id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","created_at":1775993023,"task_id":"3cd95f53-e1c4-45c3-8ef4-59f5d6def0dc","id":"98861ecb-38c7-478c-890f-33bffe2aaef2","position":1,"thought":"你好！有什么可以帮助你的吗？无论是写作还是纠错，我都会尽力协助你。请告诉我你的需求。","observation":"","tool":"","tool_labels":{},"tool_input":"","message_files":[]}

data: {"event":"message_end","conversation_id":"9ad8afe6-a00c-499c-a5f0-b2bfe5d74a49","message_id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","created_at":1775993023,"task_id":"3cd95f53-e1c4-45c3-8ef4-59f5d6def0dc","id":"b4dec4ef-b287-4bfb-a0fa-d7c3bd10bd60","metadata":{"annotation_reply":null,"retriever_resources":[],"usage":{"prompt_tokens":16,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":25,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":41,"total_price":"0","currency":"RMB","latency":2.060744408518076,"time_to_first_token":null,"time_to_generate":null}},"files":null}

```

#### chatflow

##### 定义

```js
event: message LLM 返回文本块事件，即：完整的文本以分块的方式输出。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    answer (string) LLM 返回文本块内容
    created_at (int) 创建时间戳，如：1705395332
event: message_file 文件事件，表示有新文件需要展示
    id (string) 文件唯一ID
    type (string) 文件类型，目前仅为image
    belongs_to (string) 文件归属，user或assistant，该接口返回仅为 assistant
    url (string) 文件访问地址
    conversation_id (string) 会话ID
event: message_end 消息结束事件，收到此事件则代表流式返回结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    metadata (object) 元数据
        usage (Usage) 模型用量信息
        retriever_resources (array[RetrieverResource]) 引用和归属分段列表
event: tts_message TTS 音频流事件，即：语音合成输出。内容是Mp3格式的音频块，使用 base64 编码后的字符串，播放的时候直接解码即可。(开启自动播放才有此消息)
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    audio (string) 语音合成之后的音频块使用 Base64 编码之后的文本内容，播放的时候直接 base64 解码送入播放器即可
    created_at (int) 创建时间戳，如：1705395332
event: tts_message_end TTS 音频流结束事件，收到这个事件表示音频流返回结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    audio (string) 结束事件是没有音频的，所以这里是空字符串
    created_at (int) 创建时间戳，如：1705395332
event: message_replace 消息内容替换事件。 开启内容审查和审查输出内容时，若命中了审查条件，则会通过此事件替换消息内容为预设回复。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    conversation_id (string) 会话 ID
    answer (string) 替换内容（直接替换 LLM 所有回复文本）
    created_at (int) 创建时间戳，如：1705395332
event: workflow_started workflow 开始执行
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 workflow_started
    data (object) 详细内容
        id (string) workflow 执行 ID
        workflow_id (string) 关联 Workflow ID
        created_at (timestamp) 开始时间
event: node_started node 开始执行
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 node_started
    data (object) 详细内容
        id (string) workflow 执行 ID
        node_id (string) 节点 ID
        node_type (string) 节点类型
        title (string) 节点名称
        index (int) 执行序号，用于展示 Tracing Node 顺序
        predecessor_node_id (string) 前置节点 ID，用于画布展示执行路径
        inputs (object) 节点中所有使用到的前置节点变量内容
        created_at (timestamp) 开始时间
event: node_finished node 执行结束，成功失败同一事件中不同状态
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 node_finished
    data (object) 详细内容
        id (string) node 执行 ID
        node_id (string) 节点 ID
        index (int) 执行序号，用于展示 Tracing Node 顺序
        predecessor_node_id (string) optional 前置节点 ID，用于画布展示执行路径
        inputs (object) 节点中所有使用到的前置节点变量内容
        process_data (json) Optional 节点过程数据
        outputs (json) Optional 输出内容
        status (string) 执行状态 running / succeeded / failed / stopped
        error (string) Optional 错误原因
        elapsed_time (float) Optional 耗时(s)
        execution_metadata (json) 元数据
            total_tokens (int) optional 总使用 tokens
            total_price (decimal) optional 总费用
            currency (string) optional 货币，如 USD / RMB
        created_at (timestamp) 开始时间
event: workflow_finished workflow 执行结束，成功失败同一事件中不同状态
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    workflow_run_id (string) workflow 执行 ID
    event (string) 固定为 workflow_finished
        data (object) 详细内容
        id (string) workflow 执行 ID
        workflow_id (string) 关联 Workflow ID
        status (string) 执行状态 running / succeeded / failed / stopped
        outputs (json) Optional 输出内容
        error (string) Optional 错误原因
        elapsed_time (float) Optional 耗时(s)
        total_tokens (int) Optional 总使用 tokens
        total_steps (int) 总步数（冗余），默认 0
        created_at (timestamp) 开始时间
        finished_at (timestamp) 结束时间
event: error 流式输出过程中出现的异常会以 stream event 形式输出，收到异常事件后即结束。
    task_id (string) 任务 ID，用于请求跟踪和下方的停止响应接口
    message_id (string) 消息唯一 ID
    status (int) HTTP 状态码
    code (string) 错误码
    message (string) 错误消息
event: ping 每 10s 一次的 ping 事件，保持连接存活。
```
##### 示例

```js
event: ping

data: {"event":"workflow_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","workflow_id":"82f1cec1-76c6-4f6d-aa91-c3013587124c","inputs":{"sys.files":[],"sys.user_id":"abc-123","sys.app_id":"18ae3c31-8dd0-40ca-9c07-100d7744b01c","sys.workflow_id":"82f1cec1-76c6-4f6d-aa91-c3013587124c","sys.workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","sys.query":"你好","sys.dialogue_count":1},"created_at":1775993860,"reason":"initial"}}

data: {"event":"node_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"d97f30c3-1da2-4758-8a48-d3c59a359456","node_id":"1775628297864","node_type":"start","title":"用户输入","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775993860,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"d97f30c3-1da2-4758-8a48-d3c59a359456","node_id":"1775628297864","node_type":"start","title":"用户输入","index":1,"predecessor_node_id":null,"inputs":{"sys.files":[],"sys.user_id":"abc-123","sys.app_id":"18ae3c31-8dd0-40ca-9c07-100d7744b01c","sys.workflow_id":"82f1cec1-76c6-4f6d-aa91-c3013587124c","sys.workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","sys.query":"你好","sys.conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","sys.dialogue_count":1},"inputs_truncated":false,"process_data":{},"process_data_truncated":false,"outputs":{"sys.files":[],"sys.user_id":"abc-123","sys.app_id":"18ae3c31-8dd0-40ca-9c07-100d7744b01c","sys.workflow_id":"82f1cec1-76c6-4f6d-aa91-c3013587124c","sys.workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","sys.query":"你好","sys.conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","sys.dialogue_count":1},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.000085,"execution_metadata":null,"created_at":1775993860,"finished_at":1775993860,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"adbb030c-1d81-4171-8f9f-a2f8910c1238","node_id":"1775993197764","node_type":"http-request","title":"HTTP 请求","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775993860,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"adbb030c-1d81-4171-8f9f-a2f8910c1238","node_id":"1775993197764","node_type":"http-request","title":"HTTP 请求","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{"request":"GET / HTTP/1.1\r\nHost: www.baidu.com\r\n\r\n"},"process_data_truncated":false,"outputs":{"status_code":200,"body":"<html>xxx</html>","headers":{"bdpagetype":"1","bdqid":"0xd2a856e1006886be","connection":"keep-alive","content-encoding":"gzip","content-type":"text/html; charset=utf-8","date":"Sun, 12 Apr 2026 11:37:41 GMT","server":"BWS/1.1","set-cookie":"H_PS_PSSID=60279_63140_67861_68166_68221_68263_68297_68373_68419_68455_68438_68540_68520_68623_68612_68672_68736_68745_68545_68731_68774_68795_68808_68885_68905_68832_68920_68951_68982_68996_69003_69012_69017_69013_69021_69026_68551_69073_69034_69083_69093_69089; path=/; expires=Mon, 12-Apr-27 11:37:41 GMT; domain=.baidu.com, BDSVRTM=3; path=/, BD_HOME=1; path=/","strict-transport-security":"max-age=172800","tr_id":"super_0xd2a856e1006886be","traceid":"1775993861393735169015179478068426737342","x-ua-compatible":"IE=Edge,chrome=1","x-xss-protection":"1;mode=block","transfer-encoding":"chunked"},"files":[]},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":1.830684,"execution_metadata":null,"created_at":1775993860,"finished_at":1775993861,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"a950925c-5450-46c4-8749-a986675a9ab1","node_id":"1775993259991","node_type":"llm","title":"LLM","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775993861,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"message","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","id":"d92e3d00-935c-46e7-bc33-4132729f35e2","answer":"你好👋！有什么","from_variable_selector":["1775993259991","text"]}

data: {"event":"message","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","id":"d92e3d00-935c-46e7-bc33-4132729f35e2","answer":"可以帮助你的吗？","from_variable_selector":["1775993259991","text"]}

data: {"event":"message","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","id":"d92e3d00-935c-46e7-bc33-4132729f35e2","answer":"/","from_variable_selector":["answer","answer"]}

data: {"event":"node_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"a950925c-5450-46c4-8749-a986675a9ab1","node_id":"1775993259991","node_type":"llm","title":"LLM","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{"model_mode":"chat","prompts":[{"role":"system","text":"你是一个智能机器人","files":[]},{"role":"user","text":"你好","files":[]}],"usage":{"prompt_tokens":12,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":12,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":24,"total_price":"0","currency":"RMB","latency":4.533,"time_to_first_token":4.351,"time_to_generate":0.182},"finish_reason":"stop","model_provider":"langgenius/zhipuai/zhipuai","model_name":"glm-4-flash"},"process_data_truncated":false,"outputs":{"text":"你好👋！有什么可以帮助你的吗？","reasoning_content":"","usage":{"prompt_tokens":12,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":12,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":24,"total_price":"0","currency":"RMB","latency":4.533,"time_to_first_token":4.351,"time_to_generate":0.182},"finish_reason":"stop"},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":4.543483,"execution_metadata":{"total_tokens":24,"total_price":"0","currency":"RMB"},"created_at":1775993861,"finished_at":1775993866,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"ed712e12-f7ec-4d17-8e1f-1c1040d143be","node_id":"answer","node_type":"answer","title":"直接回复","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775993866,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"ed712e12-f7ec-4d17-8e1f-1c1040d143be","node_id":"answer","node_type":"answer","title":"直接回复","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{},"process_data_truncated":false,"outputs":{"answer":"你好👋！有什么可以帮助你的吗？/","files":[]},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.000097,"execution_metadata":null,"created_at":1775993866,"finished_at":1775993866,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"5391c42e-f11a-4081-9633-60146bf70557","node_id":"1775993742495","node_type":"http-request","title":"HTTP 请求 2","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775993866,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"message","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","id":"d92e3d00-935c-46e7-bc33-4132729f35e2","answer":"{\n  \"slideshow\": {\n    \"author\": \"Yours Truly\", \n    \"date\": \"date of publication\", \n    \"slides\": [\n      {\n        \"title\": \"Wake up to WonderWidgets!\", \n        \"type\": \"all\"\n      }, \n      {\n        \"items\": [\n          \"Why <em>WonderWidgets</em> are great\", \n          \"Who <em>buys</em> WonderWidgets\"\n        ], \n        \"title\": \"Overview\", \n        \"type\": \"all\"\n      }\n    ], \n    \"title\": \"Sample Slide Show\"\n  }\n}\n","from_variable_selector":["1775993742495","body"]}

data: {"event":"node_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"5391c42e-f11a-4081-9633-60146bf70557","node_id":"1775993742495","node_type":"http-request","title":"HTTP 请求 2","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{"request":"GET /json HTTP/1.1\r\nHost: httpbin.org\r\n\r\n"},"process_data_truncated":false,"outputs":{"status_code":200,"body":"{\n  \"slideshow\": {\n    \"author\": \"Yours Truly\", \n    \"date\": \"date of publication\", \n    \"slides\": [\n      {\n        \"title\": \"Wake up to WonderWidgets!\", \n        \"type\": \"all\"\n      }, \n      {\n        \"items\": [\n          \"Why <em>WonderWidgets</em> are great\", \n          \"Who <em>buys</em> WonderWidgets\"\n        ], \n        \"title\": \"Overview\", \n        \"type\": \"all\"\n      }\n    ], \n    \"title\": \"Sample Slide Show\"\n  }\n}\n","headers":{"date":"Sun, 12 Apr 2026 11:37:46 GMT","content-type":"application/json","content-length":"429","connection":"keep-alive","server":"gunicorn/19.9.0","access-control-allow-origin":"*","access-control-allow-credentials":"true"},"files":[]},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.372397,"execution_metadata":null,"created_at":1775993866,"finished_at":1775993866,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"node_started","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"e203a062-589b-4be0-a8f6-cefe49390aa6","node_id":"1775993570298","node_type":"answer","title":"直接回复 2","index":1,"predecessor_node_id":null,"inputs":null,"inputs_truncated":false,"created_at":1775993867,"extras":{},"iteration_id":null,"loop_id":null,"agent_strategy":null}}

data: {"event":"node_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"e203a062-589b-4be0-a8f6-cefe49390aa6","node_id":"1775993570298","node_type":"answer","title":"直接回复 2","index":1,"predecessor_node_id":null,"inputs":{},"inputs_truncated":false,"process_data":{},"process_data_truncated":false,"outputs":{"answer":"{\n  \"slideshow\": {\n    \"author\": \"Yours Truly\", \n    \"date\": \"date of publication\", \n    \"slides\": [\n      {\n        \"title\": \"Wake up to WonderWidgets!\", \n        \"type\": \"all\"\n      }, \n      {\n        \"items\": [\n          \"Why <em>WonderWidgets</em> are great\", \n          \"Who <em>buys</em> WonderWidgets\"\n        ], \n        \"title\": \"Overview\", \n        \"type\": \"all\"\n      }\n    ], \n    \"title\": \"Sample Slide Show\"\n  }\n}\n","files":[]},"outputs_truncated":false,"status":"succeeded","error":null,"elapsed_time":0.000252,"execution_metadata":null,"created_at":1775993867,"finished_at":1775993867,"files":[],"iteration_id":null,"loop_id":null}}

data: {"event":"message_end","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","id":"d92e3d00-935c-46e7-bc33-4132729f35e2","metadata":{"annotation_reply":null,"retriever_resources":[],"usage":{"prompt_tokens":12,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":12,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":24,"total_price":"0","currency":"RMB","latency":4.533,"time_to_first_token":6.557,"time_to_generate":0.723}},"files":[]}

data: {"event":"workflow_finished","conversation_id":"5c788d53-3148-4a04-9b2d-47f1e5cc274c","message_id":"d92e3d00-935c-46e7-bc33-4132729f35e2","created_at":1775993860,"task_id":"9d244c23-b6d2-4fb3-b9d1-1779e5fa965c","workflow_run_id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","data":{"id":"a2d83893-4c10-4c9c-83db-1b79fe921dc6","workflow_id":"82f1cec1-76c6-4f6d-aa91-c3013587124c","status":"succeeded","outputs":{"answer":"你好👋！有什么可以帮助你的吗？/{\n  \"slideshow\": {\n    \"author\": \"Yours Truly\", \n    \"date\": \"date of publication\", \n    \"slides\": [\n      {\n        \"title\": \"Wake up to WonderWidgets!\", \n        \"type\": \"all\"\n      }, \n      {\n        \"items\": [\n          \"Why <em>WonderWidgets</em> are great\", \n          \"Who <em>buys</em> WonderWidgets\"\n        ], \n        \"title\": \"Overview\", \n        \"type\": \"all\"\n      }\n    ], \n    \"title\": \"Sample Slide Show\"\n  }\n}\n","files":[]},"error":null,"elapsed_time":7.074545,"total_tokens":24,"total_steps":6,"created_by":{"id":"ebbd213f-b42c-4505-abde-25dc42b0e8a4","user":"abc-123"},"created_at":1775993860,"finished_at":1775993867,"exceptions_count":0,"files":[]}}


```

## agentMaker (sse请求)

### 入参header

#S008026表示为该appId的iamtoken
Authorization = IAM token - S008026

### 入参body

```jsonc
{
    "agentUuid": "agentMaker平台下，机器人的id",
    "userInput":"用户输入",
    "sessionId":"会话id，无值新会话，有值当前会话",
    "w3Account":"用户账号",
    "needSave":true // 固定值
}
```

### 出参body

```jsonc
event:事件
data:{
    "errors":[{"status":"错误码", "title":"错误标题", "detail":"错误详情"}], // 异常情况1：erros为null，错误信息在content中，异常情况2：errors不为null，错误信息在errors中
    "meta":null, // 详见下面错误码
    "meta":null,
    "data":{
        "id":"1",//无意义
        "type":"AgentDialogueVO",//<无意义
        "attributes":{
            "requestId":"traceId",
            "agentStatus":"agent状态，和外层event一致",
            "status":"预留字段",
            "content":"内容",
            "sessionId":"会话id",
            "toolResult":[ // 每次智慧执行一个工具，列表种永源只有一个
                {
                    "result":"接口的原始返回json",
                    "Param":{
                        "url":"",
                        "headers":"json",
                        "body":"json",
                        "query":"json" // 方路径
                    },
                    "toolType":"api_executor: api插件；tean_knowledge_executor: 知识库工具; flowchain_executor: flowchain工具",
                    "toolName":"工具名称",
                    "errorMessage":"TOOL_ERROR时errorMessage会返回错误信息"
                }
            ]
        }
    }
}
```

event值和含义

```jsonc
{
    "PROCESSING":"接口收到请求后会立即返回", // 忽略处理
    "PLANNING":"模型返回了规划的结果", // 忽略处理
    "TOOL_EXECUTE":"模型准备调用技能，包含名称和模型提槽的参数", // 忽略处理
    "TOOL_RESULT":"模型调用技能完成，返回包含技能的完整入参，出参",
    "TOOL_NOT_FOUND":"模型调用技能失败，技能不存在", // 忽略处理
    "SUMMARY":"模型返回了总结的结果,流式返回，每次之返回一段", // agentmaker的思考和恢复内容都放在summary中，思考内容放在<think></think>标签内，需要提取出来传给前端，</think>标签后，正式会打钱，会有 \n 空格 和空字符串内容需要识别出来忽略掉，开头无效字符忽略后，正式恢复需要返回给前端
    "END":"问答结束", // 忽略处理
    "ERROR":"agent运行异常", // 异常情况1：erros为null，错误信息在content中，异常情况2：errors不为null，错误信息在errors中
    "ASK_USER":"agnet对用户发起了追问", // 目前转为了纯文本
    "USER_CONFIRM":"内容为agentmaker的we卡，需要转为im的卡片消息，用户确认后，会返回确认结果，交互流程：xxx",
    "HITL":"FlowChain消息回复事件。", // 忽略处理
    "CUSTOM_EVENT":"知识agent默认返回的事件", // 忽略处理
    "ASYNC":"FlowChain异步事件，当用户配置了工作流，而这个工作流有时配置成了异步，当agent运行这个工作流是，就会返回这个事件，整个返回与ToolResult事件相同",// 忽略处理
    "RETRIVE":"检索refer_list内容" // 忽略处理
}
```
错误示例：
```jsonc
[
    {
        "status":"500",
        "title":"Internal Server Error",
        "detail":"服务未知异常"
    }
]

```
返回示例

```jsonc
event:PROCESSING
data:{"errors":null, "meta":null, "data":{"id":"1", "type":"AgentDialogueVO", "attributes":{"requestId":"112233", "agentStatus":"PROCESSING", "status":null, "content":null, "sessionId":"778899", "toolResult":null}}}

event:ASK_USER
data:{"errors":null, "meta":null, "data":{"id":"1", "type":"AgentDialogueVO", "attributes":{"requestId":"112233", "agentStatus":"ASK_USER", "status":null, "content":"你好，我是个智能助手", "sessionId":"778899", "toolResult":null}}}
```

## uniknow (普通http请求)

### 入参header

```
#- athena表示为该appId的soatoken
Authorization: SOAtoken - athena
origin-tenant-id: 改机器人所属的appId
```

### 入参body

```jsonc
{
    "input_text": "你好", // 必填，问题输入
    "user_id":"c1234", // 必填 用户id
    "set_meta_data": {
        "cookie":"个人cookie"
    },
    "robot_uuid":"" // 必填 uniknow平台的机器人id
}
```

### 出参

#### 有结果时

```jsonc
{
    "data":[
        {
            "taskInfo": {
                "slots":{
                    "result":{
                        "data":"机器人回复",  // 回复内容
                        "requestId":"请求id"
                    }
                }
            }
        }
    ]
}
```

#### 无结果时

```jsonc
{
    "data":[
        {
            "chatScriptContent":"抱歉，我还在学习中，请换个问题试试呗~"
        }
    ]
}
```

## athena http + sse

流程：先执行一个http操作返回id，再执行sse(带上id)获取对话结果


### 入参header
Authorization=集成账号token
cookie=个人cookie
x-tenant-id=租户id
x-welink-id=个人账号
### 入参body http

```jsonc
{
    "botId":"机器人id",
    "skillType":"skill",
    "skillId":"技能id",
    "msgType":"消息类型",
    "msgBody":"消息体",
    "clientType":"设备类型",
    "clientLang":"zh",
    "clientSystemClang":"客户端系统语言",
    "version":"20260414",//客户端版本
    "imGroupId":"im群id",
    "topicId":"会话主题id", //
    "channel":"sse",
    "extraParameters":{} // 额外参数
}
```

### 入参body sse
无

get请求，id放url中  ?id=sse id

### 出参body http
```jsonc
{
    "data":"long类型的sse id",
    "code":"200",
    "message":"ok"
}
```
### 出参body sse
todo