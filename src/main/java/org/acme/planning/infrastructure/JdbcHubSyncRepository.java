package org.acme.planning.infrastructure;

import org.acme.planning.application.HubCalendarClient.CalendarEvent;
import org.acme.planning.application.HubSyncRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Outbox protocol of the spec; every statement runs in its own short autocommit transaction. */
@ApplicationScoped
public class JdbcHubSyncRepository implements HubSyncRepository {
    // A newer PENDING row reconciles the same slot: the old row must not overwrite its state.
    private static final String NEWER_PENDING = """
            EXISTS (SELECT 1 FROM dbo.hub_sync_outbox p WHERE p.slot_id=o.slot_id AND p.id<>o.id AND p.status='PENDING')""";
    private static final String CLAIM = """
            UPDATE c SET status='PROCESSING',locked_until=DATEADD(second,60,SYSUTCDATETIME()),attempts=attempts+1
            OUTPUT inserted.id,inserted.slot_id,inserted.pp_user_id,inserted.[day],inserted.attempts
            """;
    private static final String NOT_HELD = """
            NOT EXISTS (SELECT 1 FROM dbo.hub_sync_outbox p WHERE p.slot_id=o.slot_id AND p.id<>o.id
                AND p.status='PROCESSING' AND p.locked_until>SYSUTCDATETIME())""";
    private static final String DUE = """
            ((o.status='PENDING' AND o.next_attempt_at<=SYSUTCDATETIME())
             OR (o.status='PROCESSING' AND o.locked_until<SYSUTCDATETIME()))""";

    private final DataSource ds;
    private final ObjectMapper json;

    public JdbcHubSyncRepository(DataSource ds, ObjectMapper json) {
        this.ds = ds;
        this.json = json;
    }

    private interface Read<T> {
        T read(ResultSet r) throws SQLException;
    }

    private <T> List<T> query(Connection c, String sql, Read<T> read, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (var r = s.executeQuery()) {
                var out = new ArrayList<T>();
                while (r.next()) out.add(read.read(r));
                return out;
            }
        }
    }

    private <T> List<T> query(String sql, Read<T> read, Object... args) {
        try (var c = ds.getConnection()) {
            return query(c, sql, read, args);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Hub synchronization state", e);
        }
    }

    private void update(String sql, Object... args) {
        try (var c = ds.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update Hub synchronization state", e);
        }
    }

    private String array(Object values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Message message(ResultSet r) throws SQLException {
        return new Message(r.getLong("id"), r.getLong("slot_id"), r.getInt("pp_user_id"),
                r.getDate("day").toLocalDate(), r.getInt("attempts"));
    }

    @Override
    public List<Message> claim(Collection<Long> slotIds) {
        if (slotIds.isEmpty()) return List.of();
        return query(CLAIM + """
                FROM dbo.hub_sync_outbox c WITH (ROWLOCK,UPDLOCK,READPAST)
                WHERE c.status='PENDING' AND c.slot_id IN (SELECT CAST([value] AS bigint) FROM OPENJSON(?))
                  AND NOT EXISTS (SELECT 1 FROM dbo.hub_sync_outbox p WHERE p.slot_id=c.slot_id AND p.id<>c.id
                      AND p.status='PROCESSING' AND p.locked_until>SYSUTCDATETIME())
                """, JdbcHubSyncRepository::message, array(slotIds));
    }

    @Override
    public List<Message> claimDue(int limit) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                var rows = new ArrayList<>(query(c, "WITH c AS (SELECT TOP (" + limit + ") o.* "
                        + "FROM dbo.hub_sync_outbox o WITH (ROWLOCK,UPDLOCK,READPAST) WHERE " + DUE + " AND " + NOT_HELD
                        + " ORDER BY o.id) " + CLAIM, JdbcHubSyncRepository::message));
                if (!rows.isEmpty()) {
                    // The rest of these person-days is processed together, in the spec's order.
                    var keys = rows.stream().map(m -> Map.of("u", m.userId(), "d", m.day().toString())).distinct().toList();
                    rows.addAll(query(c, "WITH c AS (SELECT o.* FROM dbo.hub_sync_outbox o WITH (ROWLOCK,UPDLOCK,READPAST) "
                            + "WHERE " + DUE + " AND " + NOT_HELD + " AND EXISTS (SELECT 1 FROM OPENJSON(?) "
                            + "WITH (u int '$.u', d date '$.d') k WHERE k.u=o.pp_user_id AND k.d=o.[day])) " + CLAIM,
                            JdbcHubSyncRepository::message, array(keys)));
                }
                c.commit();
                return rows;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot claim Hub outbox messages", e);
        }
    }

    @Override
    public Job job(long slotId) {
        return query("""
                SELECT h.hub_event_id,s.id AS live_slot_id,s.start_at,s.end_at,p.work_order_id,m.pp_member_id
                FROM (SELECT CAST(? AS bigint) AS slot_id) k
                LEFT JOIN dbo.slot_hub_sync h ON h.slot_id=k.slot_id
                LEFT JOIN dbo.work_order_time_slot s ON s.id=k.slot_id
                LEFT JOIN dbo.work_order_time_plan p ON p.id=s.time_plan_id
                LEFT JOIN dbo.pp_team_member m ON m.id=p.member_id
                """, r -> {
            // A slot of a team plan cannot exist; a slot without member is treated as removed.
            CalendarEvent event = r.getObject("live_slot_id") == null || r.getObject("pp_member_id") == null ? null
                    : new CalendarEvent("service-portal:slot:" + slotId, r.getInt("pp_member_id"), r.getInt("work_order_id"),
                            r.getTimestamp("start_at", utc()).toInstant(), r.getTimestamp("end_at", utc()).toInstant());
            return new Job(slotId, r.getString("hub_event_id"), event);
        }, slotId).getFirst();
    }

    @Override
    public void eventId(long slotId, String hubEventId) {
        update("UPDATE dbo.slot_hub_sync SET hub_event_id=?,updated_at=SYSUTCDATETIME() WHERE slot_id=?", hubEventId, slotId);
    }

    @Override
    public void synced(Message m, boolean slotDeleted) {
        update("""
                UPDATE dbo.hub_sync_outbox SET status='DONE',processed_at=SYSUTCDATETIME(),locked_until=NULL,last_error=NULL
                WHERE id=?
                """, m.id());
        if (slotDeleted) {
            update("DELETE h FROM dbo.slot_hub_sync h WHERE h.slot_id=? AND NOT EXISTS (SELECT 1 FROM dbo.hub_sync_outbox o "
                    + "WHERE o.slot_id=h.slot_id AND o.status IN ('PENDING','PROCESSING'))", m.slotId());
        } else {
            slotState(m, "SYNCED", null, null, true);
        }
    }

    @Override
    public void rejected(Message m) {
        update("""
                UPDATE dbo.hub_sync_outbox SET status='DONE',processed_at=SYSUTCDATETIME(),locked_until=NULL,
                    last_error='HUB_OVERLAP' WHERE id=?
                """, m.id());
        slotState(m, "REJECTED", "HUB_OVERLAP", "Hub calendar time is already occupied", false);
    }

    @Override
    public void retry(Message m, Duration backoff, String error) {
        update("UPDATE o SET status=CASE WHEN " + NEWER_PENDING + " THEN 'DONE' ELSE 'PENDING' END,"
                + "processed_at=CASE WHEN " + NEWER_PENDING + " THEN SYSUTCDATETIME() ELSE NULL END,"
                + "next_attempt_at=DATEADD(second,?,SYSUTCDATETIME()),last_error=?,locked_until=NULL "
                + "FROM dbo.hub_sync_outbox o WHERE o.id=?", backoff.toSeconds(), truncate(error, 2000), m.id());
        update("UPDATE dbo.slot_hub_sync SET error_message=?,updated_at=SYSUTCDATETIME() WHERE slot_id=? AND status='PENDING'",
                truncate(error, 1000), m.slotId());
    }

    @Override
    public void dead(Message m, String errorCode, String error) {
        update("""
                UPDATE dbo.hub_sync_outbox SET status='DEAD',processed_at=SYSUTCDATETIME(),locked_until=NULL,last_error=?
                WHERE id=?
                """, truncate(error, 2000), m.id());
        slotState(m, "FAILED", errorCode, truncate(error, 1000), false);
    }

    @Override
    public void release(Message m) {
        update("UPDATE o SET status=CASE WHEN " + NEWER_PENDING + " THEN 'DONE' ELSE 'PENDING' END,"
                + "processed_at=CASE WHEN " + NEWER_PENDING + " THEN SYSUTCDATETIME() ELSE NULL END,"
                + "attempts=CASE WHEN attempts>0 THEN attempts-1 ELSE 0 END,next_attempt_at=SYSUTCDATETIME(),locked_until=NULL "
                + "FROM dbo.hub_sync_outbox o WHERE o.id=? AND o.status='PROCESSING'", m.id());
    }

    private void slotState(Message m, String status, String code, String error, boolean synced) {
        // Written only if no newer PENDING message exists for the slot; otherwise it stays PENDING.
        update("UPDATE h SET status=?,error_code=?,error_message=?,updated_at=SYSUTCDATETIME()"
                + (synced ? ",synced_at=SYSUTCDATETIME()" : "")
                + " FROM dbo.slot_hub_sync h WHERE h.slot_id=? AND NOT EXISTS (SELECT 1 FROM dbo.hub_sync_outbox o "
                + "WHERE o.slot_id=h.slot_id AND o.status='PENDING' AND o.id<>?)", status, code, error, m.slotId(), m.id());
    }

    @Override
    public Map<Long, Status> statuses(Collection<Long> slotIds) {
        if (slotIds.isEmpty()) return Map.of();
        var out = new HashMap<Long, Status>();
        for (var row : query("""
                SELECT h.slot_id,h.status,h.error_code FROM dbo.slot_hub_sync h
                WHERE h.slot_id IN (SELECT CAST([value] AS bigint) FROM OPENJSON(?))
                """, r -> Map.entry(r.getLong("slot_id"), new Status(r.getString("status"), r.getString("error_code"))),
                array(slotIds))) {
            out.put(row.getKey(), row.getValue());
        }
        return out;
    }

    @Override
    public Optional<Instant> oldestPending() {
        return query("SELECT MIN(created_at) AS oldest FROM dbo.hub_sync_outbox WHERE status='PENDING'", r -> {
            var t = r.getTimestamp("oldest", utc());
            return t == null ? null : t.toInstant();
        }).stream().filter(Objects::nonNull).findFirst();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
