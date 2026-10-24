package org.acme.planning.infrastructure;

import org.acme.planning.application.*;
import org.acme.planning.domain.Models.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.*;

@Path("/v3") 
@Produces(MediaType.APPLICATION_JSON) 
@Consumes(MediaType.APPLICATION_JSON) 
public class PlanningResource {
    private final TimePlanService plans;
    private final TimePlanMoveService moves;
    private final SchedulesService schedules;
    private final TimePlanLogService logs;
    private final CommonPlanningService common;
    private final HubSyncService hub;

    // Not @Transactional: each service method commits, then its own Hub messages are processed (spec: Processing).
    public PlanningResource(TimePlanService plans, TimePlanMoveService moves,
            SchedulesService schedules, TimePlanLogService logs, CommonPlanningService common, HubSyncService hub) {
        this.plans = plans;
        this.moves = moves;
        this.schedules = schedules;
        this.logs = logs;
        this.common = common;
        this.hub = hub;
    }

    @GET 
    @Path("/planner/config") public Object config() {
        return common.config();
    }

    @GET 
    @Path("/planner") 
    public Object planner(@QueryParam("day") LocalDate day, @QueryParam("dealerId") Integer dealer) {
        return plans.planner(day, dealer);
    }

    @GET 
    @Path("/work-order-time-plans") 
    public Object list(@QueryParam("day") LocalDate day, @QueryParam("workOrderId") Integer order) {
        return plans.list(day, order);
    }

    @GET 
    @Path("/work-order-time-plans/{id}") 
    public Object find(@PathParam("id") long id) {
        return plans.find(id);
    }

    @GET 
    @Path("/work-order-time-plans/{id}/log") 
    public Object log(@PathParam("id") long id) {
        return logs.logs(id);
    }

    @POST 
    @Path("/work-order-time-plans") 
    public Object create(Create c) {
        return plans.create(c);
    }

    @PATCH 
    @Path("/work-order-time-plans/{id}") 
    public Object change(@PathParam("id") long id, Change c) {
        return plans.change(id, c);
    }

    @DELETE 
    @Path("/work-order-time-plans/{id}") 
    public Object delete(@PathParam("id") long id, @QueryParam("expectedVersion") Integer version) {
        return hub.afterCommit(plans.delete(id, version));
    }

    @PUT 
    @Path("/work-order-time-plans/{id}/slots") 
    public Object slots(@PathParam("id") long id, Schedule s) {
        if (s == null) {
            throw org.acme.planning.domain.Problem.bad("Body required");
        }
        return hub.afterCommit(schedules.schedule(List.of(new Schedule(id, s.expectedVersion(), s.slots()))));
    }

    public record Batch(List<Schedule> timePlans) {
    }

    @PUT 
    @Path("/work-order-time-plans/schedules") 
    public Object schedules(Batch b) {
        return hub.afterCommit(schedules.schedule(b == null?null:b.timePlans()));
    }

    @POST 
    @Path("/work-order-time-plans/{id}/move") 
    public Object move(@PathParam("id") long id, Move m) {
        return hub.afterCommit(moves.move(id, m, null));
    }

    @POST 
    @Path("/work-order-time-plans/{id}/move/preview") 
    public Object preview(@PathParam("id") long id, Move m) {
        return moves.preview(id, m);
    }

    @POST 
    @Path("/work-order-time-plans/{id}/slots/{slot}/move") 
    public Object moveSlot(@PathParam("id") long id, @PathParam("slot") long slot, Move m) {
        return hub.afterCommit(moves.move(id, m, slot));
    }
}
