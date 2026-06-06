package com.sudiptapaul.bedrock.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GuardrailResult {
    private boolean blocked;
    private String maskedText;
    private String triggerType;
    private String triggerReason;
}
