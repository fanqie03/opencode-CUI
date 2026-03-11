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
public class ProtocolMessagePart {

    private String partId;
    private Integer partSeq;
    private String type;
    private String content;

    private String toolName;
    private String toolCallId;
    private String status;
    private Object input;
    private String output;
    private String error;
    private String title;

    private String header;
    private String question;
    private List<String> options;

    private String permissionId;
    private String permType;
    private Object metadata;
    private String response;

    private String fileName;
    private String fileUrl;
    private String fileMime;
}
