package com.contractguard.application.llm.contract;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.port.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves every agent response contract decodes from its documented JSON shape. */
class ContractDecodingTest {

    private final JsonCodec codec = new JacksonJsonCodec();

    @Test
    void explanationsResponseDecodes() {
        var result = codec.decode("""
                {"explanations":[{"changeId":"c1","explanation":"breaks","uncertainty":""}]}""",
                ExplanationsResponse.class);
        assertThat(result.ok()).isTrue();
        ExplanationsResponse.Item item = result.value().explanations().get(0);
        assertThat(item.changeId()).isEqualTo("c1");
        assertThat(item.explanation()).isEqualTo("breaks");
        assertThat(item.uncertainty()).isEmpty();
    }

    @Test
    void investigatorToolRequestDecodes() {
        var result = codec.decode("""
                {"action":"read_source_file","search":null,
                 "read":{"path":"src/A.java","startLine":1,"endLine":40},"assessments":null}""",
                InvestigatorAction.class);
        assertThat(result.ok()).isTrue();
        assertThat(result.value().action()).isEqualTo(InvestigatorAction.READ);
        assertThat(result.value().read().path()).isEqualTo("src/A.java");
        assertThat(result.value().read().startLine()).isEqualTo(1);
        assertThat(result.value().read().endLine()).isEqualTo(40);
    }

    @Test
    void investigatorSearchAndFinishDecode() {
        var search = codec.decode("""
                {"action":"search_repository","search":{"query":"fullName","glob":null,"maxResults":30},
                 "read":null,"assessments":null}""", InvestigatorAction.class);
        assertThat(search.ok()).isTrue();
        assertThat(search.value().action()).isEqualTo(InvestigatorAction.SEARCH);
        assertThat(search.value().search().query()).isEqualTo("fullName");
        assertThat(search.value().search().maxResults()).isEqualTo(30);

        var finish = codec.decode("""
                {"action":"finish","search":null,"read":null,"assessments":[
                  {"apiChangeId":"c1","component":"A.java","severity":"HIGH","confidence":"HIGH",
                   "failureMode":"404","recommendedAction":"update path",
                   "assumptions":["live code"],"evidenceIds":["e1"]}]}""", InvestigatorAction.class);
        assertThat(finish.ok()).isTrue();
        assertThat(finish.value().action()).isEqualTo(InvestigatorAction.FINISH);
        AssessmentDraft draft = finish.value().assessments().get(0);
        assertThat(draft.apiChangeId()).isEqualTo("c1");
        assertThat(draft.severity()).isEqualTo("HIGH");
        assertThat(draft.confidence()).isEqualTo("HIGH");
        assertThat(draft.component()).isEqualTo("A.java");
        assertThat(draft.failureMode()).isEqualTo("404");
        assertThat(draft.recommendedAction()).isEqualTo("update path");
        assertThat(draft.assumptions()).containsExactly("live code");
        assertThat(draft.evidenceIds()).containsExactly("e1");
    }

    @Test
    void planDraftDecodes() {
        var result = codec.decode("""
                {"items":[{"objective":"rename","expectedFiles":["A.java"],"proposedAction":"edit",
                           "testsToUpdate":["ATest.java"],"validationCommand":"maven-verify",
                           "risk":"low","rollback":"discard branch","evidenceIds":["e1"]}]}""",
                PlanDraft.class);
        assertThat(result.ok()).isTrue();
        PlanDraft.Item item = result.value().items().get(0);
        assertThat(item.objective()).isEqualTo("rename");
        assertThat(item.expectedFiles()).containsExactly("A.java");
        assertThat(item.proposedAction()).isEqualTo("edit");
        assertThat(item.testsToUpdate()).containsExactly("ATest.java");
        assertThat(item.validationCommand()).isEqualTo("maven-verify");
        assertThat(item.risk()).isEqualTo("low");
        assertThat(item.rollback()).isEqualTo("discard branch");
        assertThat(item.evidenceIds()).containsExactly("e1");
    }

    @Test
    void fileRewritesDecode() {
        var result = codec.decode("""
                {"files":[{"path":"A.java","newContent":"class A {}"}],"notes":"done"}""",
                FileRewrites.class);
        assertThat(result.ok()).isTrue();
        FileRewrites.FileRewrite rewrite = result.value().files().get(0);
        assertThat(rewrite.path()).isEqualTo("A.java");
        assertThat(rewrite.newContent()).isEqualTo("class A {}");
        assertThat(result.value().notes()).isEqualTo("done");
    }

    @Test
    void extraFieldsAreRejectedByTheStrictCodec() {
        var result = codec.decode("""
                {"files":[],"notes":"x","hallucinated":"field"}""", FileRewrites.class);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("hallucinated");
    }
}
