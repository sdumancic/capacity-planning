package org.acme.planning.infrastructure.persistence;

import org.acme.planning.domain.Models.*;
import org.acme.planning.domain.Problem;
import org.hibernate.Session;
import org.hibernate.StaleStateException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The time plan aggregate on Hibernate ORM. Planning locks, the Hub outbox and the read models
 * stay on JDBC in the same transaction and connection, so this store never trusts a cached row:
 * <ul>
 * <li>reads detach what they load, so a find after lockPlans or after a JDBC write reads the
 * database instead of the first-level cache;</li>
 * <li>writes flush before returning, so JDBC queries later in the transaction see them, and
 * version and unique-index failures surface here as 409 instead of at commit.</li>
 * </ul>
 * Callers get immutable Plan records, never entities, so a "before" state cannot change under them.
 */
@ApplicationScoped
public class TimePlanStore {
    public Plan find(long id) {
        TimePlanEntity plan = TimePlanEntity.findById(id);
        return plan == null ? null : plans(List.of(plan)).getFirst();
    }

    public List<Plan> list(LocalDate day, Integer workOrderId) {
        var where = new ArrayList<String>();
        var args = new HashMap<String, Object>();
        if (day != null) {
            where.add("p.day = :day");
            args.put("day", day);
        }
        if (workOrderId != null) {
            where.add("p.workOrderId = :order");
            args.put("order", workOrderId);
        }
        var query = em().createQuery("FROM TimePlanEntity p" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY p.id", TimePlanEntity.class);
        args.forEach(query::setParameter);
        return plans(query.getResultList());
    }

    /** Member plans of every membership of one person on one day. */
    public List<Plan> personPlans(int user, LocalDate day) {
        // pp_team_member is not mapped; the native query still returns entities.
        var rows = em().unwrap(Session.class).createNativeQuery("""
                SELECT p.* FROM dbo.work_order_time_plan p JOIN dbo.pp_team_member m ON m.id=p.member_id
                WHERE m.pp_member_id=?1 AND p.[day]=?2 ORDER BY p.id
                """, TimePlanEntity.class).setParameter(1, user).setParameter(2, day).getResultList();
        return plans(rows);
    }

    public Plan target(int workOrderId, int teamId, Integer memberId, LocalDate day) {
        var query = em().createQuery("FROM TimePlanEntity p WHERE p.workOrderId = :order AND p.teamId = :team AND p.day = :day AND "
                + (memberId == null ? "p.memberId IS NULL" : "p.memberId = :member"), TimePlanEntity.class)
                .setParameter("order", workOrderId).setParameter("team", teamId).setParameter("day", day);
        if (memberId != null) {
            query.setParameter("member", memberId);
        }
        var rows = plans(query.getResultList());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Plan save(Plan p, Integer by) {
        TimePlanEntity plan;
        if (p.id() == 0) {
            plan = new TimePlanEntity();
            plan.workOrderId = p.workOrderId();
            plan.day = p.day();
            plan.createdBy = by;
        } else {
            plan = TimePlanEntity.findById(p.id());
            // Services check the client's version on a fresh read under lock; this rejects a stale Plan.
            if (plan == null || plan.version != p.version()) {
                throw stale();
            }
        }
        plan.teamId = p.teamId();
        plan.memberId = p.memberId();
        plan.plannedMinutes = p.plannedMinutes();
        // Always dirty, so every save updates the row and bumps @Version, slots-only changes included.
        plan.modifiedAt = Instant.now();
        plan.modifiedBy = by;
        try {
            if (plan.id == null) {
                plan.persist();   // IDENTITY: inserted here, so the id is known for the slots
            }
            var wanted = new HashSet<Long>();
            for (var s : p.slots()) {
                TimeSlotEntity slot = s.id() == null ? new TimeSlotEntity() : TimeSlotEntity.findById(s.id());
                if (slot == null) {
                    throw stale();
                }
                // A moved slot keeps its id and changes owner.
                slot.timePlanId = plan.id;
                slot.startAt = s.start();
                slot.endAt = s.end();
                if (slot.id == null) {
                    slot.persist();
                }
                wanted.add(slot.id);
            }
            // The query flushes the reassignments first, so it lists only what this plan still owns.
            for (TimeSlotEntity old : TimeSlotEntity.<TimeSlotEntity>list("timePlanId", plan.id)) {
                if (!wanted.contains(old.id)) {
                    old.delete();
                }
            }
            em().flush();
        } catch (PersistenceException e) {
            throw failure(e);
        }
        long id = plan.id;
        em().clear();
        return find(id);
    }

    public void delete(Plan p) {
        TimePlanEntity plan = TimePlanEntity.findById(p.id());
        if (plan == null || plan.version != p.version()) {
            throw stale();
        }
        try {
            // Removed in this order, so Hibernate deletes the slots before the plan they reference.
            TimeSlotEntity.<TimeSlotEntity>list("timePlanId", plan.id).forEach(TimeSlotEntity::delete);
            plan.delete();
            em().flush();
        } catch (PersistenceException e) {
            throw failure(e);
        }
        em().clear();
    }

    private List<Plan> plans(List<TimePlanEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<TimeSlotEntity> slots = TimeSlotEntity.list("timePlanId IN ?1 ORDER BY startAt, id",
                rows.stream().map(p -> p.id).toList());
        var byPlan = new HashMap<Long, List<Slot>>();
        for (var s : slots) {
            byPlan.computeIfAbsent(s.timePlanId, k -> new ArrayList<>())
                    .add(new Slot(s.id, s.startAt, s.endAt, s.hubStatus));
        }
        var out = rows.stream().map(p -> new Plan(p.id, p.workOrderId, p.teamId, p.memberId, p.day, p.plannedMinutes,
                p.version, List.copyOf(byPlan.getOrDefault(p.id, List.of())))).toList();
        // The next read must see what lockPlans or a JDBC writer found in the row, not this copy.
        rows.forEach(em()::detach);
        slots.forEach(em()::detach);
        return out;
    }

    private static Problem stale() {
        return new Problem(409, "VERSION_CONFLICT", "Time plan changed; reload");
    }

    private static RuntimeException failure(PersistenceException e) {
        if (e instanceof OptimisticLockException || e instanceof StaleStateException) {
            return stale();
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && (s.getErrorCode() == 2601 || s.getErrorCode() == 2627)) {
                return new Problem(409, "VERSION_CONFLICT", "Assignment changed concurrently; reload");
            }
        }
        return e;
    }

    private static EntityManager em() {
        return TimePlanEntity.getEntityManager();
    }
}
