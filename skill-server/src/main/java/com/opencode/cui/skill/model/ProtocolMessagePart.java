package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 协议层消息分片 DTO。
 * 用于前端 WebSocket 协议中单条消息内的结构化分片（text/tool/permission/question/file）。
 *
 * <p>
 * 一条 assistant 消息可以包含多个分片：
 * 文本块、推理块、工具调用、文件引用等。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProtocolMessagePart {

    /** 分片唯一标识 */
    private String partId;

    /** 分片序号 */
    private Integer partSeq;

    /** 分片类型（text/reasoning/tool/file/step-start/step-finish） */
    private String type;

    /** 文本内容（text/reasoning 类型使用） */
    private String content;

    // ==================== 工具调用字段 ====================

    /** 工具名称 */
    private String toolName;

    /** 工具调用 ID */
    private String toolCallId;

    /** 工具执行状态（pending/running/completed/error） */
    private String status;

    /** 工具输入参数 */
    private Object input;

    /** 工具输出结果 */
    private String output;

    /** 工具执行错误信息 */
    private String error;

    /** 工具标题 */
    private String title;

    // ==================== 交互式问答字段 ====================

    /** 问答头部说明 */
    private String header;

    /** 问题内容 */
    private String question;

    /** 选项列表 */
    private List<String> options;

    /** 是否已回答 */
    private Boolean answered;

    // ==================== 权限请求字段 ====================

    /** 权限请求 ID */
    private String permissionId;

    /** 权限类型 */
    private String permType;

    /** 权限元数据 */
    private Object metadata;

    /** 权限应答内容 */
    private String response;

    // ==================== 文件字段 ====================

    /** 文件名 */
    private String fileName;

    /** 文件 URL */
    private String fileUrl;

    /** 文件 MIME 类型 */
    private String fileMime;

    // ==================== Subagent 字段 ====================

    /** Subagent 会话 ID */
    private String subagentSessionId;

    /** Subagent 名称 */
    private String subagentName;
}
