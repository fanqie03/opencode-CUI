| 服务名称 | AppNotify请求        |      |      |
|------|--------------------| ---- | ---- |
| 请求路径 | {域名}/v1/app-notify |      |      |
| 请求方式 | post               |      |      |
| 鉴权方式 | Authorization:token     |      |      |

请求体

| 属性名           | 类型         | 必填 | 说明                                                         |
| ---------------- | ------------ | ---- | ------------------------------------------------------------ |
| client_notify_id | string       | Y    | 通知唯一标识                                                 |
| notify_scope     | int          | Y    | 接收者模式：入参为2即可                                      |
| notify_tenant    | string       | Y    | 企业标识，配置                                               |
| notify_group     | long         | N    | 指定群组，不涉及                                             |
| notify_accounts  | List<String> | N    | 指定通知的账号                                               |
| notify_module    | string       | Y    | 通知归属应用模块标识，配置                                   |
| notify_data      | string       | Y    | json字符串，每个模块定制化的通知消息，各个模块按自己协议解析json |



返回参数

| 属性名           | 类型         | 必填 | 说明 |
| ---------------- | ------------ | ---- | ---- |
| error            | ErrorInfo    | Y    |      |
| client_notify_id | string       | Y    |      |
| server_notify_id | long         | Y    |      |
| Invalid_account  | List<String> | N    |      |

ErrorInfo

| 属性名称   | 类型   | 必填 | 说明     |
| ---------- | ------ | ---- | -------- |
| error_code | string | N    | 异常编码 |
| error_msg  | string | N    | 异常信息 |