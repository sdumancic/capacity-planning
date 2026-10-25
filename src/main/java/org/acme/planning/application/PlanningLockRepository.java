package org.acme.planning.application;

import org.acme.planning.domain.Models.Plan;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

/** Planning locks participate in the caller's planning transaction. */
public interface PlanningLockRepository {
    /**
     * Takes every team-day and user-day lock of these plans in one sorted pass;
     * call once per transaction.
     */
    void lockAssignees(Collection<Plan> plans);

    void lockPlans(Collection<Long> planIds);

    interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Session lock hub-user-day:&lt;user&gt;:&lt;day&gt;, held outside any
     * transaction; empty when not granted in time.
     */
    Optional<Held> lockHubUserDay(int userId, LocalDate day, Duration timeout);
}
