package com.contractguard.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicRemediationTest {

    @Test
    void endpointPathReplacementUpdatesConstantsAndBuiltUrls() {
        String content = """
                static final String PATH = "/customers/{id}";
                server.expect(requestTo("https://api.example.com/customers/42"));
                """;
        String result = DeterministicRemediation.replaceEndpointPath(
                content, "/customers/{id}", "/v2/customers/{id}");
        assertThat(result).contains("\"/v2/customers/{id}\"");
        assertThat(result).contains("api.example.com/v2/customers/42");
    }

    @Test
    void endpointPathReplacementIsIdempotent() {
        String once = DeterministicRemediation.replaceEndpointPath(
                "url = /customers/7", "/customers/{id}", "/v2/customers/{id}");
        String twice = DeterministicRemediation.replaceEndpointPath(
                once, "/customers/{id}", "/v2/customers/{id}");
        assertThat(once).isEqualTo("url = /v2/customers/7");
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void propertyRenameCoversFieldAndAccessors() {
        String content = """
                private String fullName;
                public String getFullName() { return fullName; }
                public void setFullName(String fullName) { this.fullName = fullName; }
                json = "{\\"fullName\\":\\"x\\"}";
                """;
        String result = DeterministicRemediation.renameProperty(content, "fullName", "displayName");
        assertThat(result).doesNotContain("fullName").doesNotContain("FullName");
        assertThat(result).contains("private String displayName;")
                .contains("getDisplayName")
                .contains("setDisplayName")
                .contains("\\\"displayName\\\"");
    }

    @Test
    void propertyRenameDoesNotTouchPartialWordMatches() {
        String content = "String fullNameHistory; int notfullName;";
        String result = DeterministicRemediation.renameProperty(content, "fullName", "displayName");
        assertThat(result).isEqualTo(content);
    }

    @Test
    void enumConstantAndCaseBranchesAreRemoved() {
        String content = """
                public enum Status {
                    ACTIVE,
                    SUSPENDED,
                    CLOSED
                }
                boolean ok = switch (s) {
                    case ACTIVE -> true;
                    case SUSPENDED -> false;
                    case CLOSED -> false;
                };
                """;
        String result = DeterministicRemediation.removeEnumValue(content, "SUSPENDED");
        assertThat(result).doesNotContain("SUSPENDED");
        assertThat(result).contains("ACTIVE,").contains("CLOSED");
    }

    @Test
    void removingTheLastEnumConstantFixesTheTrailingComma() {
        String content = """
                public enum Status {
                    ACTIVE,
                    SUSPENDED
                }
                """;
        String result = DeterministicRemediation.removeEnumValueLines(content, "SUSPENDED");
        assertThat(result).doesNotContain("SUSPENDED");
        assertThat(result).contains("    ACTIVE\n");
    }

    @Test
    void testMethodsReferencingTheValueAreRemovedEntirely() {
        String content = """
                class PolicyTest {

                    @Test
                    void activeWorks() {
                        assertTrue(policy.canPlaceOrder(active));
                    }

                    @Test
                    void suspendedBlocked() {
                        Customer c = customer(CustomerStatus.SUSPENDED);
                        assertFalse(policy.canPlaceOrder(c));
                    }

                    @Test
                    void closedBlocked() {
                        assertFalse(policy.canPlaceOrder(closed));
                    }
                }
                """;
        String result = DeterministicRemediation.removeTestMethodsReferencing(content, "SUSPENDED");
        assertThat(result).doesNotContain("suspendedBlocked").doesNotContain("SUSPENDED");
        assertThat(result).contains("activeWorks").contains("closedBlocked");
    }

    @Test
    void proseMentionsOfTheEnumValueAreLeftAlone() {
        String content = "Statuses: `ACTIVE`, `SUSPENDED` or `CLOSED`.";
        assertThat(DeterministicRemediation.removeEnumValue(content, "SUSPENDED")).isEqualTo(content);
    }

    @Test
    void transformAppliesAllChangeTypesTogether() {
        String content = """
                String path = "/customers/{id}";
                String fullName;
                case SUSPENDED -> false;
                """;
        String result = DeterministicRemediation.transform(content, List.of(
                new DeterministicRemediation.ChangeSpec("ENDPOINT_RENAMED", "/customers/{id}", "/v2/customers/{id}"),
                new DeterministicRemediation.ChangeSpec("PROPERTY_RENAMED", "fullName", "displayName"),
                new DeterministicRemediation.ChangeSpec("ENUM_VALUE_REMOVED", "SUSPENDED", null),
                new DeterministicRemediation.ChangeSpec("PROPERTY_ADDED", null, "preferredLanguage")));
        assertThat(result).contains("/v2/customers/{id}")
                .contains("displayName")
                .doesNotContain("SUSPENDED");
    }

    @Test
    void specsForProjectsTheFactsTransformNeedsFromRealApiChanges() {
        ApiChange rename = new ApiChange("chg-1", ChangeType.PROPERTY_RENAMED, Classification.BREAKING,
                null, null, "Customer", "fullName", "fullName", "displayName", "REASON", "{}", null);

        List<DeterministicRemediation.ChangeSpec> specs = DeterministicRemediation.specsFor(List.of(rename));

        assertThat(specs).containsExactly(
                new DeterministicRemediation.ChangeSpec("PROPERTY_RENAMED", "fullName", "displayName"));
    }

    @Test
    void applyToChangedFilesKeepsOnlyFilesThatActuallyChanged() {
        List<ApiChange> changes = List.of(new ApiChange("chg-1", ChangeType.PROPERTY_RENAMED,
                Classification.BREAKING, null, null, "Customer", "fullName", "fullName", "displayName",
                "REASON", "{}", null));
        Map<String, String> files = Map.of(
                "Customer.java", "private String fullName;",
                "Unrelated.java", "class Unrelated {}");

        Map<String, String> changed = DeterministicRemediation.applyToChangedFiles(files, changes);

        assertThat(changed).containsOnlyKeys("Customer.java");
        assertThat(changed.get("Customer.java")).contains("displayName");
    }
}
