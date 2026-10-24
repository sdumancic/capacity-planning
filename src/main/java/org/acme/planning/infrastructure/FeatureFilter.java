package org.acme.planning.infrastructure;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
public class FeatureFilter implements ContainerRequestFilter {
    @ConfigProperty(name = "capacity.v3.enabled", defaultValue = "false") boolean enabled;
    public void filter(ContainerRequestContext request) {
        if (!enabled && request.getUriInfo().getPath().startsWith("v3")) {
            request.abortWith(Response.status(404).build());
        }
    }
}
