package org.acme.planning.infrastructure.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Row of dbo.work_order_time_plan; TimePlanStore maps it to the immutable domain Plan. */
@Entity
@Table(name = "work_order_time_plan", schema = "dbo")
public class TimePlanEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    public int workOrderId;

    @Column(name = "team_id", nullable = false)
    public int teamId;

    @Column(name = "member_id")
    public Integer memberId;

    @Column(name = "`day`", nullable = false, updatable = false)
    public LocalDate day;

    @Column(name = "planned_minutes", nullable = false)
    public int plannedMinutes;

    @Version
    public int version;

    @Column(name = "created_by", updatable = false)
    public Integer createdBy;

    @Column(name = "modified_at", nullable = false)
    public Instant modifiedAt;

    @Column(name = "modified_by")
    public Integer modifiedBy;
}
