package com.example.loancollections.config;

import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed application settings bound from the {@code app.*} properties. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotBlank String datasetDir) {
    public Path datasetPath() {
        return Path.of(datasetDir);
    }
}
