package com.opencode.cui.skill.service.task;

import com.opencode.cui.skill.model.AsyncTask;
import com.opencode.cui.skill.model.enums.AsyncTaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 异步任务处理器容器。
 * 收集所有 {@link TaskProcessor} 实现，按 {@link AsyncTaskType} 路由。
 */
@Slf4j
@Component
public class AsyncTaskContainer {

    private final Map<AsyncTaskType, TaskProcessor> processors;

    public AsyncTaskContainer(List<TaskProcessor> processorList) {
        this.processors = processorList.stream()
                .collect(Collectors.toMap(
                        TaskProcessor::getTaskType,
                        Function.identity()));
        log.info("AsyncTaskContainer registered {} processors: {}",
                processors.size(), processors.keySet());
    }

    /**
     * 按任务类型路由到对应处理器。
     *
     * @return true 表示处理器已执行，false 表示无对应处理器
     */
    public boolean process(AsyncTask task) {
        TaskProcessor processor = processors.get(task.getTaskType());
        if (processor == null) {
            log.warn("No TaskProcessor registered for type: {}", task.getTaskType());
            return false;
        }
        processor.process(task);
        return true;
    }
}
