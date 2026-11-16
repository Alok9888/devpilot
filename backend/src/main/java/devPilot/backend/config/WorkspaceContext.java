package devPilot.backend.config;

import java.util.UUID;

import org.springframework.stereotype.Component;

import devPilot.backend.services.AnonymousWorkspace;

/** Holds the workspace ID for the lifetime of the current HTTP request. */
@Component
public class WorkspaceContext {
    private final ThreadLocal<UUID> current = new ThreadLocal<>();

    public UUID getWorkspaceId() {
        UUID workspaceId = current.get();
        return workspaceId != null ? workspaceId : AnonymousWorkspace.ID;
    }

    void setWorkspaceId(UUID workspaceId) {
        current.set(workspaceId);
    }

    void clear() {
        current.remove();
    }
}
