package org.acme.planning.infrastructure;

import org.acme.planning.domain.Problem;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CurrentUserFilter implements ContainerRequestFilter {
    static final String HEADER = "X-User-Id";
    private final CurrentUser user;

    public CurrentUserFilter(CurrentUser user) {
        this.user = user;
    }

    public void filter(ContainerRequestContext request) {
        var value = request.getHeaderString(HEADER);
        if (value == null || value.isBlank()) return;
        try {
            int id = Integer.parseInt(value.trim());
            if (id <= 0) throw new NumberFormatException();
            user.id(id);
        } catch (NumberFormatException e) {
            throw Problem.bad(HEADER + " must be a positive pp_user id");
        }
    }
}
