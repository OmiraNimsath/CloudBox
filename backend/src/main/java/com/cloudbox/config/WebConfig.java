package com.cloudbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cloudbox.service.NodeRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CORS configuration — allows the Vite dev server (localhost:5173) to call the API.
 * Also installs an interceptor that returns 503 for all user-facing endpoints when
 * this node is simulated-failed, so the frontend cycles away just like it would for
 * a hard-failed node.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final NodeRegistry nodeRegistry;

    public WebConfig(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:5174")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
                    throws Exception {
                String path = req.getRequestURI();
                // Let health, internal, and admin endpoints through so other nodes and the
                // admin UI can still detect / manage the simulated failure state.
                if (path.contains("/api/health")
                        || path.contains("/api/internal/")
                        || path.contains("/api/admin/")) {
                    return true;
                }
                if (nodeRegistry.isSelfFailed()) {
                    res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return false;
                }
                return true;
            }
        });
    }
}
