package org.acme.planning.infrastructure;

import org.acme.planning.domain.Problem;
import jakarta.ws.rs.ext.*;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Provider
public class ProblemMapper implements ExceptionMapper<Problem> {
    public Response toResponse(Problem p) {
        return Response.status(p.status).entity(Map.of("code", p.code, "message", p.getMessage(), "details", p.details)).build();
    }
}
