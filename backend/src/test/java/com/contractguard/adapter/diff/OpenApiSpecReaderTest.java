package com.contractguard.adapter.diff;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiSpecReaderTest {

    private final OpenApiSpecReader reader = new OpenApiSpecReader();

    @TempDir
    Path tempDir;

    @Test
    void missingFileFailsTyped() {
        assertThatThrownBy(() -> reader.read(tempDir.resolve("nope.yaml")))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }

    @Test
    void garbageContentFailsTyped() throws IOException {
        Path file = Files.writeString(tempDir.resolve("junk.yaml"), "::: not yaml at all {{{");
        assertThatThrownBy(() -> reader.read(file))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }

    @Test
    void swaggerTwoIsRejectedAsUnsupported() throws IOException {
        Path file = Files.writeString(tempDir.resolve("v2.yaml"), """
                swagger: "2.0"
                info: {title: Old, version: "1"}
                paths: {}
                """);
        assertThatThrownBy(() -> reader.read(file))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isIn(FailureCategory.INVALID_OPENAPI, FailureCategory.UNSUPPORTED_FEATURE));
    }

    @Test
    void extractsEndpointsSchemasAndEnums() throws IOException {
        Path file = Files.writeString(tempDir.resolve("api.yaml"), """
                openapi: 3.0.3
                info: {title: T, version: "1"}
                paths:
                  /things/{id}:
                    get:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema: {type: string}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Thing'}
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Thing'}
                      responses:
                        '204': {description: done}
                components:
                  schemas:
                    Thing:
                      type: object
                      required: [name]
                      properties:
                        name: {type: string}
                        state: {type: string, enum: ["OPEN", "SHUT"]}
                """);

        SpecModel model = reader.read(file);

        assertThat(model.endpoints()).containsKeys("GET /things/{id}", "POST /things/{id}");
        SpecModel.Endpoint get = model.endpoints().get("GET /things/{id}");
        assertThat(get.responseSchema()).isEqualTo("Thing");
        assertThat(get.parameters()).containsOnlyKeys("id");
        SpecModel.ParameterShape id = get.parameters().get("id");
        assertThat(id.location()).isEqualTo("path");
        assertThat(id.required()).isTrue();
        assertThat(id.shape().type()).isEqualTo("string");
        assertThat(get.responseStatusCodes()).containsExactly("200");
        SpecModel.Endpoint post = model.endpoints().get("POST /things/{id}");
        assertThat(post.requestBodySchema()).isEqualTo("Thing");
        assertThat(post.requestBodyRequired()).isFalse();
        assertThat(post.responseSchema()).isNull();
        assertThat(post.responseStatusCodes()).containsExactly("204");

        SpecModel.SchemaShape thing = model.schemas().get("Thing");
        assertThat(thing.required()).containsExactly("name");
        assertThat(thing.properties().get("state").enumValues()).containsExactly("OPEN", "SHUT");
    }
}
