package com.sudiptapaul.bedrock.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditLog {
    private String sessionId;
    private String timestamp;
    private String triggerType;
    private String originalInput;
    private String maskedInput;
    private String action;
    private String userId;
}
