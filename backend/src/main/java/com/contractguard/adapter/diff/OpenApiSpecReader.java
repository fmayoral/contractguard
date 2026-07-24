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
import io.swagger.v3.oas.models.security.SecurityRequirement;
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

    @SuppressWarnings("rawtypes") // Components.getSchemas() is raw in swagger-parser itself
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
                                    parameters(op.getValue()),
                                    requestBodySchema(op.getValue()),
                                    requestBodyRequired(op.getValue()),
                                    responseStatusCodes(op.getValue()),
                                    requestBodyContentTypes(op.getValue()),
                                    securitySchemes(op.getValue(), api)));
                }
            }
        }
        Map<String, SpecModel.SchemaShape> schemas = new LinkedHashMap<>();
        if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
            // Components.getSchemas() is declared Map<String, Schema> (raw) by swagger-parser
            // itself -- not our own generics sloppiness, and not fixable at this call site.
            for (Map.Entry<String, Schema> entry : new TreeMap<>(api.getComponents().getSchemas()).entrySet()) {
                schemas.put(entry.getKey(), toShape(entry.getValue()));
            }
        }
        return new SpecModel(endpoints, schemas);
    }

    @SuppressWarnings("rawtypes") // Schema.getProperties() is raw in swagger-parser itself
    private SpecModel.SchemaShape toShape(Schema<?> schema) {
        Map<String, SpecModel.PropertyShape> properties = new LinkedHashMap<>();
        // Schema.getProperties() is declared Map<String, Schema> (raw) by swagger-parser itself --
        // not our own generics sloppiness, and not fixable at this call site.
        Map<String, Schema> raw = schema.getProperties();
        if (raw != null) {
            for (Map.Entry<String, Schema> entry : new TreeMap<>(raw).entrySet()) {
                properties.put(entry.getKey(), propertyShape(entry.getValue()));
            }
        }
        Set<String> required = schema.getRequired() == null
                ? Set.of() : new LinkedHashSet<>(schema.getRequired());
        return new SpecModel.SchemaShape(properties, required);
    }

    /** Shared by schema properties and parameter schemas -- both are plain {@link Schema} nodes. */
    private SpecModel.PropertyShape propertyShape(Schema<?> schema) {
        if (schema == null) {
            return new SpecModel.PropertyShape(null, null, List.of());
        }
        List<String> enums = new ArrayList<>();
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(v -> enums.add(String.valueOf(v)));
        }
        boolean nullable = Boolean.TRUE.equals(schema.getNullable());
        SpecModel.Constraints constraints = new SpecModel.Constraints(
                asDouble(schema.getMinimum()), asDouble(schema.getMaximum()),
                schema.getMinLength(), schema.getMaxLength());
        return new SpecModel.PropertyShape(schema.getType(), schema.getFormat(), enums, nullable, constraints);
    }

    private static Double asDouble(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
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

    private boolean requestBodyRequired(Operation operation) {
        return operation.getRequestBody() != null
                && Boolean.TRUE.equals(operation.getRequestBody().getRequired());
    }

    private Set<String> responseStatusCodes(Operation operation) {
        return operation.getResponses() == null
                ? Set.of() : new LinkedHashSet<>(operation.getResponses().keySet());
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

    private Map<String, SpecModel.ParameterShape> parameters(Operation operation) {
        Map<String, SpecModel.ParameterShape> parameters = new LinkedHashMap<>();
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                parameters.put(parameter.getName(), new SpecModel.ParameterShape(
                        parameter.getIn(), Boolean.TRUE.equals(parameter.getRequired()),
                        propertyShape(parameter.getSchema())));
            }
        }
        return parameters;
    }

    private Set<String> requestBodyContentTypes(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(operation.getRequestBody().getContent().keySet());
    }

    /**
     * An empty (explicit {@code security: []}) requirement list means "no security" and must NOT
     * fall back to the global default; only an entirely absent list inherits it.
     */
    private Set<String> securitySchemes(Operation operation, OpenAPI api) {
        List<SecurityRequirement> requirements = operation.getSecurity();
        if (requirements == null) {
            requirements = api.getSecurity();
        }
        if (requirements == null) {
            return Set.of();
        }
        Set<String> schemes = new LinkedHashSet<>();
        for (SecurityRequirement requirement : requirements) {
            schemes.addAll(requirement.keySet());
        }
        return schemes;
    }
}
