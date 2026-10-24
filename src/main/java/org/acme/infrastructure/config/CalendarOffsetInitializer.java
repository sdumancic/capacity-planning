package org.acme.infrastructure.config;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.HashSet;
import java.util.Set;

/** Populates the SQL business-day offset after startup migrations have finished. */
@ApplicationScoped
public class CalendarOffsetInitializer {
    @Inject AgroalDataSource dataSource;
    @ConfigProperty(name = "capacity.business-zone") String businessZone;

    void initialize(@Observes StartupEvent event) throws SQLException {
        ZoneId zone = ZoneId.of(businessZone);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // JDBC manual commit mode alone does not start a SQL transaction before this procedure.
                try (var lock = connection.prepareStatement("IF @@TRANCOUNT=0 BEGIN TRANSACTION; DECLARE @result int; EXEC @result=sys.sp_getapplock @Resource='capacity-calendar-initializer', @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=30000; IF @result<0 THROW 51000,'Calendar initialization lock timed out',1;")) {
                    lock.execute();
                }
                // Migration owns the ISO date dimension; never insert incomplete calendar rows here.
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dbo.calendar WHERE [day] BETWEEN ? AND ?")) {
                    query.setObject(1, CalendarRange.START);
                    query.setObject(2, CalendarRange.END);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        long expected = java.time.temporal.ChronoUnit.DAYS.between(CalendarRange.START, CalendarRange.END) + 1;
                        if (rows.getLong(1) != expected) throw new SQLException("Calendar migration is incomplete");
                    }
                }
                // Refresh existing offsets only when the zone or bundled time-zone rules change.
                String rulesVersion = zone.getId() + ":" + ZoneRulesProvider.getVersions(zone.getId()).lastKey();
                String previousRules = null;
                try (var query = connection.prepareStatement("SELECT [value] FROM dbo.app_setting WHERE [key]='capacity.calendar-rules'")) {
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) previousRules = rows.getString(1);
                    }
                }
                Set<LocalDate> existing = new HashSet<>();
                if (rulesVersion.equals(previousRules)) {
                    try (var query = connection.prepareStatement("SELECT day FROM dbo.calendar WHERE utc_offset_minutes IS NOT NULL")) {
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) existing.add(rows.getDate(1).toLocalDate());
                        }
                    }
                }
                try (PreparedStatement upsert = connection.prepareStatement("UPDATE dbo.calendar SET utc_offset_minutes=? WHERE day=?")) {
                    for (LocalDate day = CalendarRange.START; !day.isAfter(CalendarRange.END); day = day.plusDays(1)) {
                        if (existing.contains(day)) continue;
                        int minutes = day.atTime(12, 0).atZone(zone).getOffset().getTotalSeconds() / 60;
                        upsert.setInt(1, minutes);
                        upsert.setObject(2, day);
                        upsert.addBatch();
                    }
                    upsert.executeBatch();
                }
                try (PreparedStatement setting = connection.prepareStatement("UPDATE dbo.app_setting SET [value]=?, updated_at=SYSUTCDATETIME() WHERE [key]='capacity.business-zone'; IF @@ROWCOUNT=0 INSERT dbo.app_setting([key],[value]) VALUES('capacity.business-zone',?)")) {
                    setting.setString(1, zone.getId());
                    setting.setString(2, zone.getId());
                    setting.executeUpdate();
                }
                try (PreparedStatement setting = connection.prepareStatement("UPDATE dbo.app_setting SET [value]=?, updated_at=SYSUTCDATETIME() WHERE [key]='capacity.calendar-rules'; IF @@ROWCOUNT=0 INSERT dbo.app_setting([key],[value]) VALUES('capacity.calendar-rules',?)")) {
                    setting.setString(1, rulesVersion);
                    setting.setString(2, rulesVersion);
                    setting.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }
}
