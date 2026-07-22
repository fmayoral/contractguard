package com.contractguard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.stream.Stream;

/** Local-first CORS: only explicitly allow-listed frontend origins may call the API cross-origin. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private static final List<String> BUILT_IN_ORIGINS =
            List.of("http://localhost:5173", "http://127.0.0.1:5173");

    private final ContractGuardProperties properties;

    public WebConfiguration(ContractGuardProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Stream.concat(BUILT_IN_ORIGINS.stream(), properties.cors().extraOrigins().stream())
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "DELETE");
    }
}
