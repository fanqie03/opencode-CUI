
# 需求：

需要在skill-server 和 ai-gateway使用普罗上报进行埋码和感知平台告警

1.普罗有个度量指标，测试人员可以看懂指标，开发看到了可以定位问题。
2.枚举第三方调用，有im，通讯录，搜索，认证，业务中心，卡片，tiny，onebox，助手广场；可能以上枚举实际项目中没调用过
3.加到慧眼告警，能够具体到哪些业务粗话了问题，一下子就知道影响到哪些业务，例举错误语法和对应的报错场景，包括业务异常和第三方接口异常
4.普罗埋码的url，补充url如果存在路径参数，怎么做到替换的，同时去掉query参数
5.首token上报到普罗的指标，也要上报到另一个平台。即WelinkTelemetryClient
6.抽象和消息相关的生命周期，最好在该生命周期内进行埋码和上报
7.探讨下第三方接口调用能否用非侵入式的方式进行埋码
8.抽象和消息相关的生命周期，在该生命周期内进行埋码和上报

慧眼告警背景：慧眼可以根据搜索日志关键词，来触发告警，
比如根据语法 loglevel:"ERROR" AND message:"im"，如果在日志中包含"im"业务的ERROR日志，就可以触发告警，提示"im"业务有异常



## skill-server要求
埋码有4个方面
1.第三方接口调用情况埋码
2.对外暴露api接口调用情况
3.流失对话效率指标（根据大脑来区分，即往普罗上报时，tag要加上大脑标签，不存在则使用 UNKNOWN 值兜底 ）
4.gateway wsClient连接情况，skill使用wsClient连接gateway，需要在skill上报当前连了多少个gateway, 总共连了多少个gateway（新增，当前，总共）


### 1.第三方接口调用情况埋码

第三方接口调用埋码，统一使用 recordApiCall(MetricServiceEnum, String url, boolean success, long durationMs) 方法进行记录

MetricServiceEnum是个枚举值，有id 和 comment 两个变量，都是string，一个是英文业务名称id，一个是中文业务名称

recordApiCall 内有 3个 tag, 分别是 {"serviceId":"metricServiceEnum.id", "serviceComment":"业务中文名", "url":"调用地址url，如果uri是动态变化的，比如说是路径参数，则将动态变化的值用 {xxx} 表示}

recordApiCall内部实现为
    1.定义tag
    2.counter 总调用次数 "external_api_call_total"
    3.counter成功/失败次数 "external_api_call_success_total" / external_api_call_failure_total
    4.耗时 timer "external_api_call_duration_seconds" 单位 ms


### 2.对外暴露api接口调用情况

使用interceptor统一统计，我在内网已经有相关代码，你这部分随便写下就行

在 postHandle 中，使用histograms 统计cost， tag为common_interface_url

### 3.流式对话效率指标

根据大脑来区分，即往普罗上报时，tag要加上大脑标签，不存在则使用 UNKNOWN 值兜底 

效率指标： 
- TTFT： 首token延迟，从输入到输出第一个token的延迟
- TPOT: 第二个token开始每个输出token的延迟
- Latency：延迟，从输入到输出最后一个token的时间
- 每秒输出token数：token输出数 / Latency

提供grafana 语法，由开发手工在grafana加上

### 4.gateway wsClient连接情况，

skill使用wsClient连接gateway，需要在skill上报当前连了多少个gateway, 总共连了多少个gateway（新增，当前，总共）

## gateway需求


### 1.gateway ws被连接情况，

gateway提供ws接口供skill连接，需要在gateway上报当前被多少个skill连接，总共连了多少次（新增，当前，总共）