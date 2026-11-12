package devPilot.backend.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();

        // Build allowed origin patterns from property
        List<String> patterns = new ArrayList<>();

        // Parse the allowed origins from config (supports comma-separated values)
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            for (String origin : allowedOrigins.split(",")) {
                origin = origin.trim();
                if (!origin.isEmpty()) {
                    patterns.add(origin);
                }
            }
        }

        // Add Vercel pattern for preview deployments
        patterns.add("https://*.vercel.app");

        // Use allowedOriginPatterns for wildcard support (Spring Security 6.x)
        config.setAllowedOriginPatterns(patterns);

        // Restrict HTTP methods to GET, POST, OPTIONS
        config.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}