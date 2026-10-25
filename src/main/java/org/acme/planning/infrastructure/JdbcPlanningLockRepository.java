package org.acme.planning.infrastructure;

import org.acme.planning.application.PlanningLockRepository;
import org.acme.planning.application.PlanningRepository;
import org.acme.planning.domain.Models.Plan;
import org.acme.planning.domain.Problem;
import jakarta.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class JdbcPlanningLockRepository implements PlanningLockRepository {
    private final DataSource ds;
    private final PlanningRepository repo;

    public JdbcPlanningLockRepository(DataSource ds, PlanningRepository repo) {
        this.ds = ds;
        this.repo = repo;
    }

    private interface Read<T> {
        T read(ResultSet result) throws SQLException;
    }

    private <T> List<T> query(String sql, Read<T> read, Object... args) {
        try (var c = ds.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
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

    private void lock(String key) {
        // Ensure the database transaction exists before acquiring a transaction-owned
        // lock.
        query("IF @@TRANCOUNT=0 BEGIN TRANSACTION; DECLARE @r int; EXEC @r=sys.sp_getapplock @Resource=?, @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=5000; SELECT @r AS result",
                r -> {
                    if (r.getInt(1) < 0) {
                        throw new Problem(409, "LOCK_TIMEOUT", "Planner busy; retry");
                    }
                    return 0;
                }, key);
    }

    public void lockAssignees(Collection<Plan> plans) {
        var keys = new TreeSet<String>();
        for (var p : plans) {
            keys.add("team-day:" + p.teamId() + ":" + p.day());
            if (p.memberId() != null) {
                keys.add("user-day:" + repo.assignee(p.teamId(), p.memberId()).userId() + ":" + p.day());
            }
        }
        keys.forEach(this::lock);
    }

    @Override
    public void lockPlans(Collection<Long> planIds) {
        for (long id : new TreeSet<>(planIds)) {
            query("SELECT id FROM dbo.work_order_time_plan WITH (UPDLOCK,HOLDLOCK) WHERE id=?",
                    rows -> rows.getLong("id"), id);
        }
    }

    @Override
    public Optional<Held> lockHubUserDay(int userId, LocalDate day, Duration timeout) {
        String key = "hub-user-day:" + userId + ":" + day;
        Connection c = null;
        try {
            // A session lock on a dedicated connection: hub calls run outside any DB
            // transaction.
            c = ds.getConnection();
            int result;
            try (var s = c
                    .prepareStatement("DECLARE @r int; EXEC @r=sys.sp_getapplock @Resource=?, @LockMode='Exclusive', "
                            + "@LockOwner='Session', @LockTimeout=?; SELECT @r AS result")) {
                s.setString(1, key);
                s.setLong(2, Math.max(0, timeout.toMillis()));
                try (var r = s.executeQuery()) {
                    r.next();
                    result = r.getInt(1);
                }
            }
            if (result < 0) {
                c.close();
                return Optional.empty();
            }
            var connection = c;
            return Optional.of(() -> {
                try (connection;
                        var s = connection.prepareStatement(
                                "EXEC sys.sp_releaseapplock @Resource=?, @LockOwner='Session'")) {
                    s.setString(1, key);
                    s.execute();
                } catch (SQLException e) {
                    throw new IllegalStateException("Cannot release " + key, e);
                }
            });
        } catch (SQLException e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // The pool discards a broken connection; its session lock ends with it.
                }
            }
            throw new IllegalStateException("Cannot acquire " + key, e);
        }
    }
}
