package org.acme.planning.infrastructure;

import org.acme.planning.application.PlanningRepository;
import org.acme.planning.application.PlanningViews;
import org.acme.planning.domain.Models.*;
import org.acme.planning.domain.Problem;
import jakarta.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class JdbcPlanningRepository implements PlanningRepository {
    private final DataSource ds;
    private final ObjectMapper json;
    private final CurrentUser currentUser;

    public JdbcPlanningRepository(DataSource ds, ObjectMapper json, CurrentUser user) {
        this.ds = ds;
        this.json = json;
        this.currentUser = user;
    }

    interface Read<T> {
        T read(ResultSet r) throws SQLException;
    }

    private void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object v = args[i];
            if (v instanceof Instant x) {
                s.setTimestamp(i + 1, Timestamp.from(x), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                continue;
            } else if (v instanceof LocalDate d) {
                v = java.sql.Date.valueOf(d);
            }
            s.setObject(i + 1, v);
        }
    }

    private <T> List<T> query(String sql, Read<T> read, Object... args) {
        try (var c = ds.getConnection(); var s = c.prepareStatement(sql)) {
            bind(s, args);
            try (var r = s.executeQuery()) {
                var out = new ArrayList<T>();
                while (r.next()) {
                    out.add(read.read(r));
                }
                return out;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private RuntimeException failure(SQLException e) {
        if (e.getErrorCode() == 2601 || e.getErrorCode() == 2627) {
            return new Problem(409, "VERSION_CONFLICT", "Assignment changed concurrently; reload");
        }
        return new IllegalStateException("Persistence operation failed", e);
    }

    private int update(String sql, Object... args) {
        try (var c = ds.getConnection(); var s = c.prepareStatement(sql)) {
            bind(s, args);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private long insert(String sql, Object... args) {
        try (var c = ds.getConnection(); var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(s, args);
            s.executeUpdate();
            try (var r = s.getGeneratedKeys()) {
                if (!r.next()) {
                    throw new SQLException("No generated id");
                }
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static Integer nullable(ResultSet r, String n) throws SQLException {
        int v = r.getInt(n);
        return r.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet r, String n) throws SQLException {
        return r.getTimestamp(n, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant();
    }

    private Plan plan(ResultSet r) throws SQLException {
        long id = r.getLong("id");
        return new Plan(id, r.getInt("work_order_id"), r.getInt("team_id"), nullable(r, "member_id"),
                r.getDate("day").toLocalDate(), r.getInt("planned_minutes"), r.getInt("version"), slots(id));
    }

    private List<Slot> slots(long id) {
        return query(
                "SELECT s.id,s.start_at,s.end_at,COALESCE(h.status,'PENDING') AS hub_status FROM dbo.work_order_time_slot s LEFT JOIN dbo.slot_hub_sync h ON h.slot_id=s.id WHERE s.time_plan_id=? ORDER BY s.start_at,s.id",
                r -> new Slot(r.getLong("id"), instant(r, "start_at"), instant(r, "end_at"), r.getString("hub_status")),
                id);
    }

    public Plan find(long id) {
        var a = query("SELECT * FROM dbo.work_order_time_plan WHERE id=?", this::plan, id);
        if (a.isEmpty()) {
            throw new Problem(404, "TIME_PLAN_NOT_FOUND", "Time plan not found");
        }
        return a.getFirst();
    }

    public List<Plan> list(LocalDate day, Integer order) {
        return query(
                "SELECT * FROM dbo.work_order_time_plan WHERE (? IS NULL OR [day]=?) AND (? IS NULL OR work_order_id=?) ORDER BY id",
                this::plan, day, day, order, order);
    }

    public List<Plan> personPlans(int user, LocalDate day) {
        return query("""
                SELECT p.* FROM dbo.work_order_time_plan p JOIN dbo.pp_team_member m ON m.id=p.member_id
                WHERE m.pp_member_id=? AND p.[day]=? ORDER BY p.id
                """, this::plan, user, day);
    }

    public Plan target(int order, int team, Integer member, LocalDate day) {
        var a = query(
                "SELECT * FROM dbo.work_order_time_plan WHERE work_order_id=? AND team_id=? AND ((member_id IS NULL AND ? IS NULL) OR member_id=?) AND [day]=?",
                this::plan, order, team, member, member, day);
        return a.isEmpty() ? null : a.getFirst();
    }

    public Order order(int id) {
        var a = query("SELECT * FROM dbo.work_order WHERE id=?", r -> new Order(r.getInt("id"), r.getInt("dealer_id"),
                r.getString("title"), instant(r, "dropoff_at"), instant(r, "pickup_at")), id);
        if (a.isEmpty()) {
            throw new Problem(404, "ORDER_NOT_FOUND", "Work order not found");
        }
        return a.getFirst();
    }

    public Assignee assignee(Integer team, Integer member) {
        if (member != null) {
            var a = query(
                    "SELECT m.id,m.team_id,m.pp_member_id,m.enabled,t.enabled AS team_enabled,t.pp_dealer_id FROM dbo.pp_team_member m JOIN dbo.pp_team t ON t.id=m.team_id WHERE m.id=?",
                    r -> new Assignee(r.getInt("team_id"), r.getInt("id"), r.getInt("pp_dealer_id"),
                            r.getInt("pp_member_id"), r.getBoolean("enabled") && r.getBoolean("team_enabled")),
                    member);
            if (a.isEmpty()) {
                throw Problem.bad("Unknown member");
            }
            if (team != null && team != a.getFirst().teamId()) {
                throw Problem.bad("Member does not belong to team");
            }
            return a.getFirst();
        }
        if (team == null) {
            throw Problem.bad("Target team or member is required");
        }
        var a = query("SELECT * FROM dbo.pp_team WHERE id=?",
                r -> new Assignee(r.getInt("id"), null, r.getInt("pp_dealer_id"), null, r.getBoolean("enabled")), team);
        if (a.isEmpty()) {
            throw Problem.bad("Unknown team");
        }
        return a.getFirst();
    }

    private static final String CAPACITY_ROWS = "SELECT * FROM dbo.fn_team_capacity_rows(?,?,?)";

    private static Capacity capacityRow(ResultSet r) throws SQLException {
        return new Capacity(r.getInt("shift_minutes"), r.getInt("blocked_minutes"),
                r.getInt("attended_minutes"), nullable(r, "productivity_percent"),
                r.getInt("capacity_minutes"), r.getInt("booked_minutes"),
                r.getInt("available_minutes"), r.getInt("pending_absence_minutes"));
    }

    public Capacity capacity(int team, Integer member, LocalDate day) {
        var assignee = assignee(team, member);
        var rows = query(CAPACITY_ROWS + """
                 WHERE team_id=? AND ((member_id IS NULL AND ? IS NULL) OR member_id=?)
                """, JdbcPlanningRepository::capacityRow, assignee.dealerId(), day, day, team, member, member);
        return rows.isEmpty() ? missingCapacity(team, member, day) : rows.getFirst();
    }

    private Capacity missingCapacity(int team, Integer member, LocalDate day) {
        // The supplied grid omits disabled/empty teams; their remaining plans still
        // count as booked.
        int booked = query("""
                SELECT COALESCE(SUM(planned_minutes),0) booked FROM dbo.work_order_time_plan
                WHERE team_id=? AND [day]=? AND (? IS NULL OR member_id=?)
                """, r -> r.getInt("booked"), team, day, member, member).getFirst();
        return new Capacity(0, 0, 0, member == null ? null : 100, 0, booked, -booked, 0);
    }

    /**
     * All capacity rows of one dealer and day, keyed team:member ("team:" for the
     * team row).
     */
    private Map<String, Capacity> capacities(int dealer, LocalDate day) {
        var out = new HashMap<String, Capacity>();
        query(CAPACITY_ROWS,
                r -> out.put(r.getInt("team_id") + ":" + Objects.toString(nullable(r, "member_id"), ""),
                        capacityRow(r)),
                dealer, day, day);
        return out;
    }

    public List<Interval> shifts(Assignee a, LocalDate day) {
        if (!a.enabled())
            return List.of();
        return query("""
                SELECT DISTINCT
                    CASE WHEN ex.start_time IS NOT NULL THEN GREATEST(s.start_time,ex.start_time)
                         ELSE s.start_time END start_time,
                    CASE WHEN ex.end_time IS NOT NULL THEN LEAST(s.end_time,ex.end_time)
                         ELSE s.end_time END end_time
                FROM dbo.calendar c
                JOIN dbo.shift_assignment sa ON sa.iso_year=c.iso_year AND sa.iso_week=c.iso_week
                    AND sa.enabled=1 AND sa.pp_partner_id=? AND sa.pp_user_id=?
                CROSS APPLY (SELECT TOP(1) sv.id FROM dbo.shift_version sv
                    WHERE sv.shift_id=sa.shift_id AND sv.effective_from<=c.[day]
                    ORDER BY sv.effective_from DESC,sv.version_number DESC) v
                JOIN dbo.shift_version_segment s ON s.shift_version_id=v.id AND s.day_of_week=c.iso_dow
                LEFT JOIN dbo.pp_dealer_working_hours_exception ex ON ex.pp_dealer_id=sa.pp_partner_id
                    AND ex.[date]=c.[day] AND ex.work_activity_type='service' AND ex.brand IS NULL
                WHERE c.[day]=? AND NOT(ex.id IS NOT NULL AND ex.start_time IS NULL)
                """, r -> new Interval(null,
                day.atTime(r.getTime("start_time").toLocalTime()).atZone(zone()).toInstant(),
                day.atTime(r.getTime("end_time").toLocalTime()).atZone(zone()).toInstant(), "SHIFT", null),
                a.dealerId(), a.userId(), day).stream().filter(x -> x.end().isAfter(x.start())).toList();
    }

    private ZoneId zone() {
        return ZoneId.of(org.eclipse.microprofile.config.ConfigProvider.getConfig().getValue("capacity.business-zone",
                String.class));
    }

    private List<Interval> breaks(Assignee a, LocalDate day) {
        return query("""
                SELECT DISTINCT b.id,b.start_time,b.end_time
                FROM dbo.calendar c
                JOIN dbo.shift_assignment sa ON sa.iso_year=c.iso_year AND sa.iso_week=c.iso_week
                    AND sa.enabled=1 AND sa.pp_partner_id=? AND sa.pp_user_id=?
                CROSS APPLY (SELECT TOP(1) sv.id FROM dbo.shift_version sv
                    WHERE sv.shift_id=sa.shift_id AND sv.effective_from<=c.[day]
                    ORDER BY sv.effective_from DESC,sv.version_number DESC) v
                JOIN dbo.shift_version_day d ON d.shift_version_id=v.id AND d.day_of_week=c.iso_dow
                JOIN dbo.shift_version_break b ON b.shift_version_day_id=d.id
                WHERE c.[day]=?
                """, r -> new Interval(r.getLong("id"),
                day.atTime(r.getTime("start_time").toLocalTime()).atZone(zone()).toInstant(),
                day.atTime(r.getTime("end_time").toLocalTime()).atZone(zone()).toInstant(), "BREAK", null),
                a.dealerId(), a.userId(), day);
    }

    public List<Interval> blocks(int user, LocalDate day, boolean pending) {
        Instant from = day.atStartOfDay(zone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone()).toInstant();
        var out = new ArrayList<>(query("""
                SELECT absence_id,start_at,end_at FROM dbo.user_absence
                WHERE pp_user_id=? AND enabled=1 AND status_code=? AND start_at<? AND end_at>?
                """, r -> new Interval(r.getLong("absence_id"), instant(r, "start_at"), instant(r, "end_at"),
                pending ? "PENDING_ABSENCE" : "ABSENCE", null), user, pending ? "PENDING" : "APPROVED", to, from));
        if (!pending)
            out.addAll(query("""
                    SELECT e.id,e.start_at,e.end_at FROM dbo.calendar_event e
                    JOIN dbo.calendar_event_participant p ON p.event_id=e.id
                    WHERE p.pp_user_id=? AND e.deleted=0 AND e.status_code NOT IN ('CANCELLED')
                        AND e.start_at<? AND e.end_at>?
                    """, r -> new Interval(r.getLong("id"), instant(r, "start_at"), instant(r, "end_at"),
                    "HUB_EVENT", null), user, to, from));
        return out;
    }

    public List<Interval> booked(int user, LocalDate day, Set<Long> excluded) {
        return query(
                "SELECT s.id,s.start_at,s.end_at,p.id AS plan_id,p.work_order_id FROM dbo.work_order_time_slot s JOIN dbo.work_order_time_plan p ON p.id=s.time_plan_id JOIN dbo.pp_team_member m ON m.id=p.member_id WHERE m.pp_member_id=? AND p.[day]=?",
                r -> excluded.contains(r.getLong("plan_id")) ? null
                        : new Interval(r.getLong("id"), instant(r, "start_at"), instant(r, "end_at"), "BOOKED",
                                r.getInt("work_order_id")),
                user, day).stream().filter(Objects::nonNull).toList();
    }

    public Plan save(Plan p) {
        long id = p.id();
        if (id == 0) {
            Integer by = CurrentUser.current(currentUser);
            id = insert(
                    "INSERT dbo.work_order_time_plan(work_order_id,team_id,member_id,[day],planned_minutes,created_by,modified_by) VALUES(?,?,?,?,?,?,?)",
                    p.workOrderId(), p.teamId(), p.memberId(), p.day(), p.plannedMinutes(), by, by);
        } else if (update(
                "UPDATE dbo.work_order_time_plan SET team_id=?,member_id=?,planned_minutes=?,version=version+1,modified_at=SYSUTCDATETIME(),modified_by=? WHERE id=? AND version=?",
                p.teamId(), p.memberId(), p.plannedMinutes(), CurrentUser.current(currentUser), id, p.version()) != 1) {
            throw new Problem(409, "VERSION_CONFLICT", "Time plan changed; reload");
        }
        var wanted = new HashSet<Long>();
        for (var s : p.slots()) {
            if (s.id() != null) {
                update("UPDATE dbo.work_order_time_slot SET time_plan_id=?,start_at=?,end_at=? WHERE id=?", id,
                        s.start(), s.end(), s.id());
                wanted.add(s.id());
            } else
                wanted.add(insert("INSERT dbo.work_order_time_slot(time_plan_id,start_at,end_at) VALUES(?,?,?)", id,
                        s.start(), s.end()));
        }
        for (var old : slots(id)) {
            if (!wanted.contains(old.id())) {
                update("DELETE dbo.work_order_time_slot WHERE id=?", old.id());
            }
        }
        return find(id);
    }

    public void delete(Plan p) {
        update("DELETE dbo.work_order_time_slot WHERE time_plan_id=?", p.id());
        if (update("DELETE dbo.work_order_time_plan WHERE id=? AND version=?", p.id(), p.version()) != 1) {
            throw new Problem(409, "VERSION_CONFLICT", "Time plan changed; reload");
        }
    }

    public void enqueue(long slot, int user, LocalDate day) {
        update("""
                UPDATE dbo.slot_hub_sync WITH (UPDLOCK,HOLDLOCK)
                SET status='PENDING',error_code=NULL,error_message=NULL,updated_at=SYSUTCDATETIME()
                WHERE slot_id=?;
                IF @@ROWCOUNT=0 INSERT dbo.slot_hub_sync(slot_id,status) VALUES(?,'PENDING');
                """, slot, slot);
        update("""
                UPDATE dbo.hub_sync_outbox WITH (UPDLOCK,HOLDLOCK)
                SET pp_user_id=?,[day]=?,next_attempt_at=SYSUTCDATETIME()
                WHERE slot_id=? AND status='PENDING';
                IF @@ROWCOUNT=0 INSERT dbo.hub_sync_outbox(slot_id,pp_user_id,[day]) VALUES(?,?,?);
                """, user, day, slot, slot, user, day);
    }

    private String snapshot(Plan p) {
        try {
            return p == null ? null : json.writeValueAsString(PlanningViews.plan(p));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void log(String op, Plan before, Plan after, Long related, UUID correlation, Integer minutes) {
        writeLog(op, before, after, related, correlation, minutes, before, after);
    }

    @Override
    public void logMove(String op, Plan before, Plan after, Long related, UUID correlation, Integer minutes,
            Plan source, Plan target) {
        writeLog(op, before, after, related, correlation, minutes, source, target);
    }

    private void writeLog(String op, Plan before, Plan after, Long related, UUID correlation, Integer minutes,
            Plan source, Plan target) {
        Plan p = after == null ? before : after;
        update("INSERT dbo.work_order_time_plan_log(correlation_id,time_plan_id,related_time_plan_id,work_order_id,operation,from_team_id,from_member_id,to_team_id,to_member_id,minutes,before_state,after_state,initiated_by,changed_by_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                correlation, p.id(), related, p.workOrderId(), op,
                source == null ? null : source.teamId(), source == null ? null : source.memberId(),
                target == null ? null : target.teamId(), target == null ? null : target.memberId(),
                minutes, snapshot(before), snapshot(after), "USER", CurrentUser.current(currentUser));
    }

    private Map<String, Object> row(ResultSet r) throws SQLException {
        var m = new LinkedHashMap<String, Object>();
        var md = r.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = r.getObject(i);
            if (v instanceof Timestamp t) {
                v = r.getTimestamp(i, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toString();
            }
            m.put(md.getColumnLabel(i), v);
        }
        return m;
    }

    public List<Map<String, Object>> logs(long id) {
        return query("SELECT * FROM dbo.work_order_time_plan_log WHERE time_plan_id=? ORDER BY id", this::row, id);
    }

    public Map<String, Object> planner(LocalDate day, Integer dealer, String businessZone) {
        var dealers = query("SELECT id,name FROM dbo.pp_dealer ORDER BY id", this::row);
        var byDealer = new HashMap<Integer, Map<String, Capacity>>();
        var teams = query("""
                SELECT id,pp_dealer_id AS dealerId,name,enabled FROM dbo.pp_team
                WHERE (? IS NULL OR pp_dealer_id=?) ORDER BY id
                """, this::row, dealer, dealer);
        for (var team : teams) {
            int id = ((Number) team.get("id")).intValue();
            // One capacity query per dealer instead of one per team and member.
            var rows = byDealer.computeIfAbsent(((Number) team.get("dealerId")).intValue(), d -> capacities(d, day));
            var teamCapacity = rows.get(id + ":");
            team.put("capacity", teamCapacity == null ? missingCapacity(id, null, day) : teamCapacity);
            var members = query("""
                    SELECT m.id,m.pp_member_id AS userId,
                        COALESCE(u.name,CONCAT('Employee ',m.pp_member_id)) name,m.enabled
                    FROM dbo.pp_team_member m LEFT JOIN dbo.pp_user u ON u.id=m.pp_member_id
                    WHERE m.team_id=? ORDER BY m.id
                    """, this::row, id);
            for (var member : members) {
                int memberId = ((Number) member.get("id")).intValue();
                int userId = ((Number) member.get("userId")).intValue();
                var a = assignee(id, memberId);
                var memberCapacity = rows.get(id + ":" + memberId);
                var events = blocks(userId, day, false);
                member.put("capacity", memberCapacity == null ? missingCapacity(id, memberId, day) : memberCapacity);
                member.put("freeIntervals", free(a, day, events));
                member.put("breaks", breaks(a, day));
                member.put("events", events.stream().filter(b -> b.reason().equals("HUB_EVENT")).toList());
            }
            team.put("members", members);
        }
        var orders = query("""
                SELECT id,dealer_id AS dealerId,title,dropoff_at AS dropoffAt,pickup_at AS pickupAt
                FROM dbo.work_order WHERE (? IS NULL OR dealer_id=?) ORDER BY id
                """, this::row, dealer, dealer);
        var absences = query("""
                SELECT a.absence_id AS id,a.pp_user_id AS userId,a.start_at AS start,
                    a.end_at AS [end],a.status_code AS status
                FROM dbo.user_absence a WHERE a.enabled=1 AND a.start_at<? AND a.end_at>?
                    AND (? IS NULL OR EXISTS(SELECT 1 FROM dbo.pp_team_member m
                        JOIN dbo.pp_team t ON t.id=m.team_id
                        WHERE m.pp_member_id=a.pp_user_id AND t.pp_dealer_id=?))
                """, this::row, day.plusDays(1).atStartOfDay(zone()).toInstant(),
                day.atStartOfDay(zone()).toInstant(), dealer, dealer);
        // The application service adds timePlans with their read conflicts.
        return Map.of("day", day, "businessZone", businessZone, "dealers", dealers, "teams", teams,
                "orders", orders, "absences", absences);
    }

    private List<Map<String, Object>> free(Assignee a, LocalDate day, List<Interval> blocked) {
        if (!a.enabled()) {
            return List.of();
        }
        var intervals = new ArrayList<>(org.acme.planning.domain.Policy.union(shifts(a, day)));
        var blocks = new ArrayList<>(blocked);
        blocks.addAll(booked(a.userId(), day, Set.of()));
        var out = new ArrayList<Map<String, Object>>();
        for (var s : intervals) {
            var pieces = new ArrayList<>(List.of(s));
            for (var b : blocks) {
                var next = new ArrayList<Interval>();
                for (var p : pieces) {
                    if (!org.acme.planning.domain.Policy.overlap(p.start(), p.end(), b.start(), b.end())) {
                        next.add(p);
                    } else {
                        if (p.start().isBefore(b.start())) {
                            next.add(new Interval(null, p.start(), b.start(), "FREE", null));
                        }
                        if (b.end().isBefore(p.end())) {
                            next.add(new Interval(null, b.end(), p.end(), "FREE", null));
                        }
                    }
                }
                pieces = next;
            }
            for (var p : pieces) {
                out.add(Map.of("start", p.start(), "end", p.end()));
            }
        }
        return out;
    }
}
