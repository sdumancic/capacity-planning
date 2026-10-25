package org.acme.planning.infrastructure.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
import java.time.Instant;

/**
 * Row of dbo.work_order_time_slot. The owning plan is a plain id, not an association: a move
 * reassigns a slot to another plan, and a collection on the old plan would still hold it.
 */
@Entity
@Table(name = "work_order_time_slot", schema = "dbo")
public class TimeSlotEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "time_plan_id", nullable = false)
    public long timePlanId;

    @Column(name = "start_at", nullable = false)
    public Instant startAt;

    @Column(name = "end_at", nullable = false)
    public Instant endAt;

    // Written by the Hub outbox over JDBC; read-only here.
    @Formula("COALESCE((SELECT h.status FROM dbo.slot_hub_sync h WHERE h.slot_id = id), 'PENDING')")
    public String hubStatus;
}
