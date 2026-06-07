package com.contractguard.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanHasherTest {

    @Test
    void hashingIsDeterministic() {
        List<PlanItem> items = List.of(Fixtures.planItem("it-1"));
        assertThat(PlanHasher.hash(items)).isEqualTo(PlanHasher.hash(items)).hasSize(64);
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        PlanItem base = Fixtures.planItem("it-1");
        PlanItem changedRisk = new PlanItem(base.id(), base.objective(), base.expectedFiles(),
                base.proposedAction(), base.testsToUpdate(), base.validationCommand(),
                "high", base.rollback(), base.evidenceIds());
        assertThat(PlanHasher.hash(List.of(base))).isNotEqualTo(PlanHasher.hash(List.of(changedRisk)));
    }

    @Test
    void itemOrderMatters() {
        PlanItem a = Fixtures.planItem("it-1");
        PlanItem b = new PlanItem("it-2", "Second objective", List.of("src/B.java"),
                "Change B", List.of(), "maven-verify", "low", "Revert", List.of("ev-1"));
        assertThat(PlanHasher.hash(List.of(a, b))).isNotEqualTo(PlanHasher.hash(List.of(b, a)));
    }

    @Test
    void lengthPrefixingPreventsDelimiterCollisions() {
        // Same concatenated characters split differently must not collide.
        PlanItem ab = itemWithObjectiveAndAction("ab", "c");
        PlanItem aBc = itemWithObjectiveAndAction("a", "bc");
        assertThat(PlanHasher.hash(List.of(ab))).isNotEqualTo(PlanHasher.hash(List.of(aBc)));
    }

    private PlanItem itemWithObjectiveAndAction(String objective, String action) {
        return new PlanItem("it-x", objective, List.of("f"), action, List.of(),
                "maven-verify", "low", "revert", List.of());
    }
}
