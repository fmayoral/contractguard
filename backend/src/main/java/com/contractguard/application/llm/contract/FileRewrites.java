package com.contractguard.application.llm.contract;

import java.util.List;

/**
 * Schema for implementation/repair agent output: complete new contents for
 * approved files only. The backend computes the actual diff (ADR-0003);
 * the model never authors patch syntax.
 */
public record FileRewrites(List<FileRewrite> files, String notes) {

    public record FileRewrite(String path, String newContent) {
    }
}
