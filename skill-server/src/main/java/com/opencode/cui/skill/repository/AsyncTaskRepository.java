package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.AsyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 异步任务的 MyBatis Mapper。
 * 对应数据库 skill_async_task 表，管理后台异步任务的创建、查询和状态更新。
 */
@Mapper
public interface AsyncTaskRepository {

    /** 插入新任务 */
    int insert(AsyncTask task);

    /** 按主键查询任务 */
    AsyncTask findById(@Param("id") Long id);

    /** 查询指定状态的任务列表（按创建时间升序，用于 FIFO 调度） */
    List<AsyncTask> findByStatus(@Param("status") String status,
                                  @Param("limit") int limit);

    /** 乐观锁更新状态：仅当 status = expectedOldStatus 时更新为 newStatus */
    int updateStatusCas(@Param("id") Long id,
                        @Param("newStatus") String newStatus,
                        @Param("expectedOldStatus") String expectedOldStatus);

    /** 更新任务状态和错误信息 */
    int updateStatusAndError(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("errorMsg") String errorMsg);

    /** 递增重试次数并重置为 PENDING */
    int incrementRetryAndReset(@Param("id") Long id,
                                @Param("maxRetry") int maxRetry);
}
