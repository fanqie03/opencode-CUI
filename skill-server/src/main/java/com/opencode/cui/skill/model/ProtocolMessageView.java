package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProtocolMessageView {

    private String id;
    private String welinkSessionId;
    private Integer seq;
    private Integer messageSeq;
    private String role;
    private String content;
    private String contentType;
    private String createdAt;
    private Object meta;
    private List<ProtocolMessagePart> parts;
}
