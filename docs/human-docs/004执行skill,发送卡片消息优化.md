# 需求

新加一个开关，开关开启后

调用skill-server 的 /send-to-im 接口时，需要在调用im发消息的时候，新加个入参  app_service_name = xxx , xxx可配置，目前为 "员工助手的消息"

需要在imMessageService.sendMessage方法添加 appServiceInfo 对象

对象为：
appServiceInfo {
    "appServiceName":"员工助手的消息",
    "appServiceId":"员工助手的消息"
}

传入 im body 的时候为小驼峰, 可以使用json工具转换的时候，加上注解 @SerializedName(app_service_name)  和 @JsonProperty(app_service_name) 转json时，字段转为小驼峰

传入im body的样子
{
    "其他字段":"xxx"
    app_service_info : {
        "app_service_name":"员工助手的消息",
        "app_service_id":"员工助手的消息"
    }
}


