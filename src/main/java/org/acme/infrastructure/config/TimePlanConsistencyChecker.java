package org.acme.infrastructure.config;

import io.agroal.api.AgroalDataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.SQLException;

/** Reports broken persisted invariants without modifying a planner's decisions. */
@ApplicationScoped
public class TimePlanConsistencyChecker {
    private static final Logger LOG = Logger.getLogger(TimePlanConsistencyChecker.class);
    @Inject AgroalDataSource dataSource;

    @Scheduled(cron = "0 0 2 * * ?", timeZone = "${capacity.business-zone}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void check() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT time_plan_id FROM dbo.vw_time_plan_inconsistency");
             var rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                LOG.errorf("Time plan consistency violation: planId=%d", rows.getLong(1));
                count++;
            }
            if (count > 0) LOG.errorf("Nightly time plan consistency check found %d inconsistent plans", count);
        } catch (SQLException failure) {
            LOG.error("Nightly time plan consistency check failed", failure);
        }
    }
}
