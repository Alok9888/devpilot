package devPilot.backend.services;

import java.util.UUID;

/** Legacy owner used only when an API request has no workspace ID header. */
public final class AnonymousWorkspace {
    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private AnonymousWorkspace() {}
}
