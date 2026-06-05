package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.opencode.cui.gateway.service.cloud.decoder.DecoderSession;
import lombok.Getter;
import lombok.Setter;

/**
 * 助手广场 decoder per-connection 状态。
 *
 * <p>用于在 {@code StandardProtocolHandler} 内承载流式 part 累积内容、
 * messageId 切换检测、{@code step.start} 单次性补齐标记。</p>
 */
@Getter
@Setter
public class AssistantSquareDecoderSession implements DecoderSession {

    /** 当前未关闭的流式 part 类型：{@code "text"}/{@code "thinking"}/{@code "planning"}；为 null 表示无 open part。 */
    private String openPartType;

    /** 当前 part 所属 messageId（切换检测用）。 */
    private String openPartMessageId;

    /** 最近一次看到的 messageId，用于 step/session tail 事件补齐。 */
    private String lastMessageId;

    /** 累积内容（done 时一并发出）。 */
    private StringBuilder openPartContent;

    /** 是否已发过 {@code step.start}。 */
    private boolean stepStarted;
}
