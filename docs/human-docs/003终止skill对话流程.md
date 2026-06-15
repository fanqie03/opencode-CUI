# 需求

前端调用 skill-server /api/skill/sessions/${welinkSsessoionId}/abort 停止技能时，需要将这个指令通过ws通道透传到gateway，gateway 调用 第三方助手的终止执行接口


终止执行请求示例
只是接口地址变了，鉴权header和入参和原来的question接口一致

通过remoteProperty中的配置项，新增type为abort类型的配置，如果配置，则在gateway终止时，调用第三方助手的终止执行接口


成功响应
http code: 200
http body:{"code":200, "msg":"success", "data":null}

错误响应例子
http code: 500
http body:{"code":500, "msg":"pc is offline", "data":null}