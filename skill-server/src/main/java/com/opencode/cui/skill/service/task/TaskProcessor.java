package com.opencode.cui.skill.service.task;

import com.opencode.cui.skill.model.AsyncTask;
import com.opencode.cui.skill.model.enums.AsyncTaskType;

/**
 * 异步任务处理器接口。
 * 每种 {@link AsyncTaskType} 对应一个实现，由 {@link AsyncTaskContainer} 统一调度。
 */
public interface TaskProcessor {

    /** 声明本处理器支持的任务类型 */
    AsyncTaskType getTaskType();

    /** 执行任务。实现需自行处理分布式锁和状态转换。 */
    void process(AsyncTask task);
}
