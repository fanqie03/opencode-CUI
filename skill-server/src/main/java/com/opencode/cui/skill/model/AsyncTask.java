package com.opencode.cui.skill.model;

import com.opencode.cui.skill.model.enums.AsyncTaskStatus;
import com.opencode.cui.skill.model.enums.AsyncTaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 异步任务实体。
 * 对应数据库 skill_async_task 表，用于延迟批量删除会话关联数据等后台操作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTask {

    /** Snowflake ID */
    private Long id;

    /** 任务类型 */
    private AsyncTaskType taskType;

    /** 任务参数（JSON 格式），如 {"sessionId":123,"messageCount":50} */
    private String payload;

    /** 任务状态 */
    @Builder.Default
    private AsyncTaskStatus status = AsyncTaskStatus.PENDING;

    /** 重试次数 */
    @Builder.Default
    private Integer retryCount = 0;

    /** 失败原因 */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
