package com.contractguard.application.llm.contract;

import java.util.List;

/**
 * Schema for one investigator step: either a registered tool request or the
 * final assessments. Exactly the fields for the chosen action may be set.
 */
public record InvestigatorAction(
        String action,
        SearchArgs search,
        ReadArgs read,
        List<AssessmentDraft> assessments) {

    public static final String SEARCH = "search_repository";
    public static final String READ = "read_source_file";
    public static final String FINISH = "finish";

    public record SearchArgs(String query, String glob, Integer maxResults) {
    }

    public record ReadArgs(String path, Integer startLine, Integer endLine) {
    }
}
