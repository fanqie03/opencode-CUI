# skill-server 新增api，使用plantuml-mit库来解析

版本为：1.2026.0

接口： /api/content/convert/text

请求体：
```json
{
    "content":"uml内容",
    "contentType":"png", -- png 或svg
    "fileType":"puml" -- puml 或 gv graphviz语法 或者plnatuml语法
}
```

响应体：
```json
{
    "code":0, -- 0正常，可能会有字符串code，前段使用string接收
    "messageCn":"success",--中文错误信息
    "messageEn":"xxx",
    "data":{
        "image":"xxx" --png返回base64, svg返回svg格式
    }
}
```

实现伪代码

def convert(content, srcType, destType):
    log.xxx
    if srcType == 'gv':
        content = "@startuml\n" + content.trim() + "\n@enduml"
    
    result = ""
    reader = new SourceStringReader(content)
    output = new ByteArrayOUtputStream()
    if destType == png:
        fileformat = new FileFormatOption(FileFormat.PNG)
        reader.outputimage(output, fileformat)
        result = base64.getEncoder.encodeToString(reader.)
    else:
        fileformat = new FileFormatOption(FileFormat.PNG)
        diagram = reader.outputimage(output, fileformat)
        if (diagram != null) {
            if ("(Error)" == diagram.getDescription) {
                log.error
                throw
            }
        } else {
            log.error
            throw
        }
        result = new String(output)
    log.xxx
    return result