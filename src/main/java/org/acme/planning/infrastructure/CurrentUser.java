package org.acme.planning.infrastructure;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.RequestScoped;

/**
 * pp_user id of the caller for created_by / modified_by / changed_by_user_id.
 * Filled from the X-User-Id header set by the authenticating gateway; null outside a request.
 */
@RequestScoped
public class CurrentUser {
    private Integer id;

    public Integer id() {
        return id;
    }

    void id(Integer id) {
        this.id = id;
    }

    static Integer current(CurrentUser user) {
        return Arc.container().requestContext().isActive() ? user.id() : null;
    }
}
