-- Correct two reproducible supplied-function defects without rewriting the source migration:
-- eager conversion of the NULL sentinel, and duplicate/overlapping assignment intervals.
CREATE OR ALTER FUNCTION dbo.fn_user_capacity (@dealerId INT, @from DATE, @to DATE)
RETURNS TABLE
AS RETURN
WITH seg_raw AS (
    SELECT sa.pp_user_id,
           c.[day],
           -- NULL offset = kalendar nije inicijaliziran: namjerna greška umjesto tihog kapaciteta 0
           CAST(COALESCE(CAST(c.utc_offset_minutes AS varchar(80)),
                         'dbo.calendar.utc_offset_minutes is not initialised') AS INT) AS utc_offset_minutes,
           CASE WHEN ex.start_time IS NOT NULL THEN GREATEST(s.start_time, ex.start_time) ELSE s.start_time END AS start_time,
           CASE WHEN ex.end_time   IS NOT NULL THEN LEAST(s.end_time, ex.end_time)       ELSE s.end_time   END AS end_time
    FROM dbo.calendar c
    JOIN dbo.shift_assignment sa
      ON sa.iso_year = c.iso_year
     AND sa.iso_week = c.iso_week
     AND sa.enabled = 1
     AND sa.pp_partner_id = @dealerId
    CROSS APPLY (
        SELECT TOP (1) sv.id
        FROM dbo.shift_version sv
        WHERE sv.shift_id = sa.shift_id
          AND sv.effective_from <= c.[day]
        ORDER BY sv.effective_from DESC, sv.version_number DESC
    ) v
    JOIN dbo.shift_version_segment s
      ON s.shift_version_id = v.id
     AND s.day_of_week = c.iso_dow
    LEFT JOIN dbo.pp_dealer_working_hours_exception ex
      ON ex.pp_dealer_id = @dealerId
     AND ex.[date] = c.[day]
     AND ex.work_activity_type = 'service'
     AND ex.brand IS NULL
    WHERE c.[day] BETWEEN @from AND @to
      AND NOT (ex.id IS NOT NULL AND ex.start_time IS NULL)      -- dealer ne radi cijeli dan
),
seg_utc AS (
    SELECT DISTINCT pp_user_id, [day],
           DATEADD(minute, -utc_offset_minutes,
                   DATEADD(second, DATEDIFF(second, CAST('00:00' AS time), start_time), CAST([day] AS datetime2(7)))) AS seg_start,
           DATEADD(minute, -utc_offset_minutes,
                   DATEADD(second, DATEDIFF(second, CAST('00:00' AS time), end_time),   CAST([day] AS datetime2(7)))) AS seg_end
    FROM seg_raw
    WHERE end_time > start_time                                  -- segment izvan prozora dealera otpada
),
-- Multiple enabled assignments can describe identical or overlapping shifts.
-- Union their UTC intervals before intersecting the independent busy union.
seg_ordered AS (
    SELECT pp_user_id, [day], seg_start, seg_end,
           MAX(seg_end) OVER (PARTITION BY pp_user_id, [day] ORDER BY seg_start, seg_end
                             ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_max_end
    FROM seg_utc
),
seg_grouped AS (
    SELECT pp_user_id, [day], seg_start, seg_end,
           SUM(CASE WHEN prev_max_end >= seg_start THEN 0 ELSE 1 END)
               OVER (PARTITION BY pp_user_id, [day] ORDER BY seg_start, seg_end ROWS UNBOUNDED PRECEDING) AS grp
    FROM seg_ordered
),
seg AS (
    SELECT pp_user_id, [day], MIN(seg_start) AS seg_start, MAX(seg_end) AS seg_end
    FROM seg_grouped
    GROUP BY pp_user_id, [day], grp
),
win AS (                                                         -- UTC granice perioda: lokalna ponoć @from do lokalne ponoći nakon @to
    SELECT DATEADD(minute, -cf.utc_offset_minutes, CAST(@from AS datetime2(7)))             AS w_from,
           DATEADD(minute, -ct.utc_offset_minutes, DATEADD(day, 1, CAST(@to AS datetime2(7)))) AS w_to
    FROM dbo.calendar cf, dbo.calendar ct
    WHERE cf.[day] = @from AND ct.[day] = @to
),
users AS (                                                       -- samo useri dealera sa šihtom u periodu
    SELECT DISTINCT pp_user_id FROM seg
),
busy_raw AS (                                                    -- absence + eventi kao intervali; is_pending = 1 samo za PENDING absence
    SELECT a.pp_user_id,
           CASE WHEN a.status_code = 'PENDING' THEN 1 ELSE 0 END AS is_pending,
           a.start_at AS s,                                       -- UTC, kako dolazi
           a.end_at   AS e
    FROM users u
    JOIN dbo.user_absence a ON a.pp_user_id = u.pp_user_id       -- ix_user_absence_user_time
    CROSS JOIN win
    WHERE a.enabled = 1
      AND a.status_code IN ('APPROVED', 'PENDING')
      AND a.start_at < win.w_to
      AND a.end_at   > win.w_from
    UNION ALL
    SELECT p.pp_user_id, 0, e.start_at, e.end_at                 -- UTC
    FROM users u
    JOIN dbo.calendar_event_participant p ON p.pp_user_id = u.pp_user_id   -- ix_calendar_event_participant_user
    JOIN dbo.calendar_event e ON e.id = p.event_id
    CROSS JOIN win
    WHERE e.deleted = 0
      AND e.status_code NOT IN ('CANCELLED')                     -- PROVJERITI stvarne kodove statusa
      AND e.start_at < win.w_to
      AND e.end_at   > win.w_from
),
busy_scoped AS (
    -- B = stvarno blokirano (approved absence ∪ eventi)
    -- A = sve zajedno (B ∪ pending); pending minute = A − B, pa se preklapanje s B ne broji dvaput
    SELECT pp_user_id, 'B' AS scope, s, e FROM busy_raw WHERE is_pending = 0
    UNION ALL
    SELECT pp_user_id, 'A' AS scope, s, e FROM busy_raw
),
busy_ordered AS (
    SELECT pp_user_id, scope, s, e,
           MAX(e) OVER (PARTITION BY pp_user_id, scope ORDER BY s, e
                        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_max_end
    FROM busy_scoped
),
busy_grouped AS (
    SELECT pp_user_id, scope, s, e,
           SUM(CASE WHEN prev_max_end >= s THEN 0 ELSE 1 END)
               OVER (PARTITION BY pp_user_id, scope ORDER BY s, e ROWS UNBOUNDED PRECEDING) AS grp
    FROM busy_ordered
),
busy AS (                                                        -- spojeni, međusobno nepreklapajući intervali po scopeu
    SELECT pp_user_id, scope, MIN(s) AS s, MAX(e) AS e
    FROM busy_grouped
    GROUP BY pp_user_id, scope, grp
),
seg_lost AS (
    SELECT g.pp_user_id, g.[day], g.seg_start, g.seg_end,
           ISNULL(SUM(CASE WHEN b.scope = 'B'
                           THEN DATEDIFF(minute, GREATEST(g.seg_start, b.s), LEAST(g.seg_end, b.e)) END), 0) AS blocked_minutes,
           ISNULL(SUM(CASE WHEN b.scope = 'A'
                           THEN DATEDIFF(minute, GREATEST(g.seg_start, b.s), LEAST(g.seg_end, b.e)) END), 0) AS blocked_all_minutes
    FROM seg g
    LEFT JOIN busy b
      ON b.pp_user_id = g.pp_user_id
     AND b.s < g.seg_end
     AND b.e > g.seg_start
    GROUP BY g.pp_user_id, g.[day], g.seg_start, g.seg_end
),
day_sum AS (
    SELECT pp_user_id,
           [day],
           SUM(DATEDIFF(minute, seg_start, seg_end))                   AS shift_minutes,
           SUM(blocked_minutes)                                        AS blocked_minutes,
           SUM(DATEDIFF(minute, seg_start, seg_end) - blocked_minutes) AS attended_minutes,
           SUM(blocked_all_minutes - blocked_minutes)                  AS pending_absence_minutes   -- informativno, minute na satu
    FROM seg_lost
    GROUP BY pp_user_id, [day]
)
SELECT d.pp_user_id,
       d.[day],
       d.shift_minutes,
       d.blocked_minutes,
       d.attended_minutes,
       ISNULL(u.productivity_percent, 100)                                   AS productivity_percent,
       d.attended_minutes * ISNULL(u.productivity_percent, 100) / 100        AS capacity_minutes,  -- INT dijeljenje = FLOOR
       d.pending_absence_minutes
FROM day_sum d
LEFT JOIN dbo.pp_user u ON u.id = d.pp_user_id;                  -- user bez retka u pp_user: 100 %
