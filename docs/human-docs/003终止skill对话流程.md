# 需求

前端调用 skill-server /api/skill/sessions/${welinkSsessoionId}/abort 停止技能时，需要将这个指令通过ws通道透传到gateway，gateway 调用 第三方助手的终止执行接口


终止执行请求示例
```
curl --location --request POST 'https://xxx/api/digital-assistant/assistant-agent-b/stream_chat_stop' \
--header 'x-hw-id: xxx' \
--header 'x-hw-appkey: xxx' \
--header 'Content-Type: application/json' \
--data-raw '{
    "topicId":"sse 请求中的topicId",
    "assistantAccount":"助理的数字分身welinkid, partnerAccount",
    "sendUserAccount":"消息发送方，用户w3账号",
    "imGroupId":"群id，非必填",
    "messageId":"回复的消息ID，非必填",
    "clientLang":"枚举，zh 或 en ,没有默认zh"
}'
```

成功响应
http code: 200
http body:{"code":200, "msg":"success", "data":null}

错误响应例子
http code: 500
http body:{"code":500, "msg":"pc is offline", "data":null}