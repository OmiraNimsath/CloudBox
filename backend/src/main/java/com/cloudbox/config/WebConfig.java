package com.cloudbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import com.cloudbox.controller.HealthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global web configuration.
 *
 * Enables CORS so the React dev server (localhost:5173) can
 * talk to any backend node (localhost:8080–8084).
 *
 * Provides RestTemplate bean for inter-node HTTP communication.
 */
@Configuration
@EnableConfigurationProperties({TimeSyncProperties.class})
public class WebConfig {

    @Autowired
    @Lazy
    private HealthController healthController;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                        if (healthController != null && healthController.isSimulatingFailure()) {
                            String path = request.getRequestURI();
                            if (!path.startsWith("/api/internal") && !path.startsWith("/api/admin")) {
                                response.setStatus(503);
                                return false;
                            }
                        }
                        return true;
                    }
                });
            }
        };
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
