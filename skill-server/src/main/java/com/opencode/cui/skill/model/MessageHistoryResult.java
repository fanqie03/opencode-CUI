package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageHistoryResult<T> {

    private List<T> content;

    private Integer size;

    @Getter(AccessLevel.NONE)
    private boolean hasMore;

    private Integer nextBeforeSeq;

    public boolean getHasMore() {
        return hasMore;
    }
}
