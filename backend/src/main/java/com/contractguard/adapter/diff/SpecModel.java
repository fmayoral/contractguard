package com.contractguard.adapter.diff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Library-independent snapshot of the parts of an OpenAPI document the diff
 * engine analyses. Keeping this model free of swagger-parser types confines
 * the external library to {@link OpenApiSpecReader} (FR-004).
 */
public record SpecModel(Map<String, Endpoint> endpoints, Map<String, SchemaShape> schemas) {

    public SpecModel {
        endpoints = Map.copyOf(endpoints);
        schemas = Map.copyOf(schemas);
    }

    /** Key format: {@code METHOD path}, e.g. {@code GET /customers/{id}}. */
    public static String endpointKey(String method, String path) {
        return method + " " + path;
    }

    /**
     * @param responseSchema name of the 2xx JSON response schema, null when absent
     * @param requestBodySchema name of the JSON request body schema, null when absent
     */
    public record Endpoint(String method, String path, String responseSchema,
            Set<String> parameterNames, String requestBodySchema) {
        public Endpoint {
            parameterNames = Set.copyOf(parameterNames);
        }
    }

    public record SchemaShape(Map<String, PropertyShape> properties, Set<String> required) {
        public SchemaShape {
            properties = Map.copyOf(properties);
            required = Set.copyOf(required);
        }
    }

    /** @param enumValues declared enum constants, empty for non-enum properties */
    public record PropertyShape(String type, String format, List<String> enumValues) {
        public PropertyShape {
            enumValues = List.copyOf(enumValues);
        }

        public boolean sameTypeAs(PropertyShape other) {
            return java.util.Objects.equals(type, other.type)
                    && java.util.Objects.equals(format, other.format);
        }
    }
}
