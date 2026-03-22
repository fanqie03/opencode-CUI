package com.opencode.cui.skill.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 分页结果包装器。
 * 模拟 Spring Data Page 结构，供前端统一解析
 * （content, totalElements, totalPages, number, size）。
 *
 * @param <T> 列表元素类型
 */
public class PageResult<T> {

    /** 当前页数据列表 */
    private List<T> content;

    /** 总记录数 */
    private long totalElements;

    /** 总页数 */
    private int totalPages;

    /** 当前页码（0-based） */
    private int number;

    /** 每页大小 */
    private int size;

    /**
     * 构造分页结果。
     *
     * @param content       当前页数据
     * @param totalElements 总记录数
     * @param page          当前页码（0-based）
     * @param size          每页大小
     */
    public PageResult(List<T> content, long totalElements, int page, int size) {
        this.content = content;
        this.totalElements = totalElements;
        this.number = page;
        this.size = size;
        this.totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
    }

    /** 获取当前页数据列表 */
    public List<T> getContent() {
        return content;
    }

    /** 获取总记录数（JSON 序列化为 "total"） */
    @JsonProperty("total")
    public long getTotalElements() {
        return totalElements;
    }

    /** 获取总页数 */
    public int getTotalPages() {
        return totalPages;
    }

    /** 获取当前页码（JSON 序列化为 "page"） */
    @JsonProperty("page")
    public int getNumber() {
        return number;
    }

    /** 获取每页大小 */
    public int getSize() {
        return size;
    }
}
