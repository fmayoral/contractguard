package com.contractguard.application.service;

import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.Hashing;
import com.contractguard.domain.ImpactEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic evidence collection (FR-006): derives search terms from each
 * change's facts, runs the bounded repository search, and produces evidence
 * records with exact file/line coordinates and a snippet content hash.
 */
public class EvidenceCollector {

    private static final int MAX_RESULTS_PER_TERM = 30;

    private record Term(String value, String relationship) {
    }

    private final RepositorySearchPort searchPort;

    public EvidenceCollector(RepositorySearchPort searchPort) {
        this.searchPort = searchPort;
    }

    public List<ImpactEvidence> collect(String repositoryId, List<ApiChange> changes) {
        List<ImpactEvidence> evidence = new ArrayList<>();
        int counter = 1;
        for (ApiChange change : changes) {
            Set<String> seenLocations = new LinkedHashSet<>();
            for (Term term : termsFor(change)) {
                for (RepositorySearchPort.SearchMatch match
                        : searchPort.search(repositoryId, term.value(), null, MAX_RESULTS_PER_TERM)) {
                    if (!seenLocations.add(match.relativePath() + ":" + match.lineNumber())) {
                        continue;
                    }
                    evidence.add(new ImpactEvidence(
                            "ev-" + counter++,
                            change.id(),
                            match.relativePath(),
                            match.lineNumber(),
                            match.lineNumber(),
                            match.lineText(),
                            term.value(),
                            term.relationship(),
                            Hashing.sha256Hex(match.lineText())));
                }
            }
        }
        return evidence;
    }

    private List<Term> termsFor(ApiChange change) {
        return switch (change.type()) {
            case ENDPOINT_RENAMED, ENDPOINT_REMOVED ->
                    List.of(new Term(templateFreePrefix(change.oldValue()), "CALLS_OLD_ENDPOINT"));
            case ENDPOINT_ADDED -> List.of();
            case PROPERTY_RENAMED -> List.of(
                    new Term(change.oldValue(), "READS_RENAMED_PROPERTY"),
                    new Term(capitalise(change.oldValue()), "READS_RENAMED_PROPERTY_ACCESSOR"));
            case PROPERTY_REMOVED -> List.of(
                    new Term(change.property(), "READS_REMOVED_PROPERTY"),
                    new Term(capitalise(change.property()), "READS_REMOVED_PROPERTY_ACCESSOR"));
            case PROPERTY_ADDED -> List.of(
                    new Term(change.newValue(), "REFERENCES_ADDED_PROPERTY"));
            case PROPERTY_TYPE_CHANGED, PROPERTY_REQUIRED_CHANGED -> List.of(
                    new Term(change.property(), "READS_CHANGED_PROPERTY"),
                    new Term(capitalise(change.property()), "READS_CHANGED_PROPERTY_ACCESSOR"));
            case ENUM_VALUE_REMOVED -> List.of(
                    new Term(change.oldValue(), "HANDLES_REMOVED_ENUM_VALUE"));
            case ENUM_VALUE_ADDED -> List.of(
                    new Term(change.property(), "SWITCHES_ON_EXTENDED_ENUM"));
            case PARAMETER_ADDED -> List.of(
                    new Term(change.property(), "REFERENCES_ADDED_PARAMETER"));
            case PARAMETER_REMOVED -> List.of(
                    new Term(change.property(), "SENDS_REMOVED_PARAMETER"));
            case PARAMETER_TYPE_CHANGED, PARAMETER_REQUIRED_CHANGED -> List.of(
                    new Term(change.property(), "SENDS_CHANGED_PARAMETER"));
            case REQUEST_BODY_ADDED, REQUEST_BODY_REMOVED -> List.of(
                    new Term(templateFreePrefix(change.path()), "CALLS_ENDPOINT_WITH_CHANGED_REQUEST_BODY"));
            case REQUEST_BODY_SCHEMA_CHANGED -> requestBodySchemaChangedTerms(change);
            case RESPONSE_STATUS_ADDED, RESPONSE_STATUS_REMOVED -> List.of(
                    new Term(templateFreePrefix(change.path()), "CALLS_ENDPOINT_WITH_CHANGED_RESPONSE_STATUS"));
            case UNKNOWN_CHANGE -> unknownTerms(change);
        };
    }

    /** The path term always applies; the old/new request body schema names are added when present. */
    private List<Term> requestBodySchemaChangedTerms(ApiChange change) {
        Map<String, Term> terms = new LinkedHashMap<>();
        String prefix = templateFreePrefix(change.path());
        terms.put(prefix, new Term(prefix, "CALLS_ENDPOINT_WITH_CHANGED_REQUEST_BODY"));
        if (change.oldValue() != null) {
            terms.put(change.oldValue(), new Term(change.oldValue(), "BUILDS_STALE_REQUEST_PAYLOAD"));
        }
        if (change.newValue() != null) {
            terms.put(change.newValue(), new Term(change.newValue(), "MAY_NEED_NEW_REQUEST_PAYLOAD"));
        }
        return List.copyOf(terms.values());
    }

    private List<Term> unknownTerms(ApiChange change) {
        Map<String, Term> terms = new LinkedHashMap<>();
        if (change.path() != null) {
            String prefix = templateFreePrefix(change.path());
            terms.put(prefix, new Term(prefix, "USES_CHANGED_ENDPOINT"));
        }
        if (change.schema() != null) {
            terms.put(change.schema(), new Term(change.schema(), "USES_CHANGED_SCHEMA"));
        }
        return List.copyOf(terms.values());
    }

    private static String templateFreePrefix(String path) {
        int brace = path.indexOf('{');
        return brace < 0 ? path : path.substring(0, brace);
    }

    private static String capitalise(String name) {
        return name == null || name.isEmpty()
                ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
