package devPilot.backend.config;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import devPilot.backend.services.AnonymousWorkspace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Resolves the browser's workspace header and makes it available during controller execution. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class WorkspaceFilter extends OncePerRequestFilter {
    private static final String WORKSPACE_HEADER = "X-Workspace-Id";

    private final WorkspaceContext workspaceContext;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(WORKSPACE_HEADER);
        UUID workspaceId;
        if (header == null || header.isBlank()) {
            workspaceId = AnonymousWorkspace.ID;
        } else {
            try {
                workspaceId = UUID.fromString(header.trim());
            } catch (IllegalArgumentException exception) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid X-Workspace-Id header");
                return;
            }
        }

        workspaceContext.setWorkspaceId(workspaceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            workspaceContext.clear();
        }
    }
}
