package com.contractguard.adapter.diff;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Parses and validates an OpenAPI 3.x document into a {@link SpecModel} (FR-002). */
public class OpenApiSpecReader {

    public SpecModel read(Path specPath) {
        if (!Files.isRegularFile(specPath)) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "specification file not found: " + specPath,
                    "Check the configured specification path.");
        }
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specPath.toString(), null, options);
        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "not a parseable OpenAPI 3.x document: %s (%s)".formatted(
                            specPath.getFileName(), firstMessage(result.getMessages())),
                    "Provide a valid OpenAPI 3.x specification.");
        }
        if (api.getOpenapi() == null || !api.getOpenapi().startsWith("3.")) {
            throw ContractGuardException.of(FailureCategory.UNSUPPORTED_FEATURE,
                    "unsupported OpenAPI version '%s' in %s".formatted(api.getOpenapi(), specPath.getFileName()),
                    "Only OpenAPI 3.x is supported.");
        }
        return toModel(api);
    }

    private static String firstMessage(List<String> messages) {
        return messages == null || messages.isEmpty() ? "no parser detail" : messages.get(0);
    }

    private SpecModel toModel(OpenAPI api) {
        Map<String, SpecModel.Endpoint> endpoints = new LinkedHashMap<>();
        if (api.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : new TreeMap<>(api.getPaths()).entrySet()) {
                for (Map.Entry<PathItem.HttpMethod, Operation> op
                        : pathEntry.getValue().readOperationsMap().entrySet()) {
                    String method = op.getKey().name();
                    String path = pathEntry.getKey();
                    endpoints.put(SpecModel.endpointKey(method, path),
                            new SpecModel.Endpoint(method, path,
                                    successResponseSchema(op.getValue()),
                                    parameterNames(op.getValue()),
                                    requestBodySchema(op.getValue())));
                }
            }
        }
        Map<String, SpecModel.SchemaShape> schemas = new LinkedHashMap<>();
        if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
            for (Map.Entry<String, Schema> entry : new TreeMap<>(api.getComponents().getSchemas()).entrySet()) {
                schemas.put(entry.getKey(), toShape(entry.getValue()));
            }
        }
        return new SpecModel(endpoints, schemas);
    }

    private SpecModel.SchemaShape toShape(Schema<?> schema) {
        Map<String, SpecModel.PropertyShape> properties = new LinkedHashMap<>();
        Map<String, Schema> raw = schema.getProperties();
        if (raw != null) {
            for (Map.Entry<String, Schema> entry : new TreeMap<>(raw).entrySet()) {
                Schema<?> prop = entry.getValue();
                List<String> enums = new ArrayList<>();
                if (prop.getEnum() != null) {
                    prop.getEnum().forEach(v -> enums.add(String.valueOf(v)));
                }
                properties.put(entry.getKey(),
                        new SpecModel.PropertyShape(prop.getType(), prop.getFormat(), enums));
            }
        }
        Set<String> required = schema.getRequired() == null
                ? Set.of() : new LinkedHashSet<>(schema.getRequired());
        return new SpecModel.SchemaShape(properties, required);
    }

    private String successResponseSchema(Operation operation) {
        if (operation.getResponses() == null) {
            return null;
        }
        for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
            if (entry.getKey().startsWith("2")) {
                String ref = jsonSchemaRef(entry.getValue().getContent());
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    private String requestBodySchema(Operation operation) {
        return operation.getRequestBody() == null
                ? null : jsonSchemaRef(operation.getRequestBody().getContent());
    }

    private String jsonSchemaRef(Content content) {
        if (content == null) {
            return null;
        }
        MediaType json = content.get("application/json");
        if (json == null || json.getSchema() == null || json.getSchema().get$ref() == null) {
            return null;
        }
        String ref = json.getSchema().get$ref();
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private Set<String> parameterNames(Operation operation) {
        Set<String> names = new LinkedHashSet<>();
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                names.add(parameter.getName());
            }
        }
        return names;
    }
}
