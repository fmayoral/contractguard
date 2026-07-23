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
     * @param responseSchema         name of the 2xx JSON response schema, null when absent
     * @param parameters              declared parameters (query/path/header/cookie), keyed by name
     * @param requestBodySchema       name of the JSON request body schema, null when absent
     * @param requestBodyRequired     ignored when {@code requestBodySchema} is null
     * @param responseStatusCodes     every declared response status code, e.g. {@code "200"}, {@code "404"}
     * @param requestBodyContentTypes media types the request body is offered in, e.g. {@code application/json}
     * @param securitySchemes         names of security schemes required (operation-level, falling back to
     *                                the document's global requirement), empty when the operation is open
     */
    public record Endpoint(String method, String path, String responseSchema,
            Map<String, ParameterShape> parameters, String requestBodySchema,
            boolean requestBodyRequired, Set<String> responseStatusCodes,
            Set<String> requestBodyContentTypes, Set<String> securitySchemes) {
        public Endpoint {
            parameters = Map.copyOf(parameters);
            responseStatusCodes = Set.copyOf(responseStatusCodes);
            requestBodyContentTypes = Set.copyOf(requestBodyContentTypes);
            securitySchemes = Set.copyOf(securitySchemes);
        }
    }

    /** @param location OpenAPI parameter location, e.g. {@code query}, {@code path}, {@code header} */
    public record ParameterShape(String location, boolean required, PropertyShape shape) {

        public boolean sameTypeAs(ParameterShape other) {
            return shape.sameTypeAs(other.shape);
        }
    }

    public record SchemaShape(Map<String, PropertyShape> properties, Set<String> required) {
        public SchemaShape {
            properties = Map.copyOf(properties);
            required = Set.copyOf(required);
        }
    }

    /**
     * @param enumValues  declared enum constants, empty for non-enum properties
     * @param nullable    whether the OpenAPI {@code nullable} keyword is set
     * @param constraints numeric/string bounds; {@link Constraints#NONE} when the schema declares none
     */
    public record PropertyShape(String type, String format, List<String> enumValues,
            boolean nullable, Constraints constraints) {
        public PropertyShape {
            enumValues = List.copyOf(enumValues);
        }

        /** Convenience constructor for shapes with no nullability/constraint information. */
        public PropertyShape(String type, String format, List<String> enumValues) {
            this(type, format, enumValues, false, Constraints.NONE);
        }

        public boolean sameTypeAs(PropertyShape other) {
            return java.util.Objects.equals(type, other.type)
                    && java.util.Objects.equals(format, other.format);
        }
    }

    /**
     * Numeric ({@code minimum}/{@code maximum}) and string ({@code minLength}/{@code maxLength}) bounds.
     * Any field is null when the schema does not declare that constraint. Regex {@code pattern}
     * tightening is deliberately not modelled -- regex containment is undecidable in general (ADR-0014).
     */
    public record Constraints(Double minimum, Double maximum, Integer minLength, Integer maxLength) {
        public static final Constraints NONE = new Constraints(null, null, null, null);
    }
}
