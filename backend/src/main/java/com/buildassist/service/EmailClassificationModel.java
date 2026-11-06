package com.buildassist.service;

public interface EmailClassificationModel {

    ClassificationResponse classify(String emailContext);

    record ClassificationResponse(String content, Integer inputTokens, Integer outputTokens) {
        boolean hasUsage() {
            return inputTokens != null && outputTokens != null;
        }
    }
}
