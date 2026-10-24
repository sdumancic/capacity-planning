CREATE VIEW dbo.vw_time_plan_inconsistency AS
SELECT p.id AS time_plan_id
FROM dbo.work_order_time_plan p
LEFT JOIN dbo.calendar c ON c.[day]=p.[day]
LEFT JOIN dbo.work_order_time_slot s ON s.time_plan_id=p.id
GROUP BY p.id,p.member_id,p.[day],p.planned_minutes,c.[day],c.utc_offset_minutes
HAVING c.[day] IS NULL OR c.utc_offset_minutes IS NULL
    OR COALESCE(SUM(CAST(s.minutes AS bigint)),0)>CEILING(p.planned_minutes/15.0)*15
    OR (p.member_id IS NULL AND COUNT(s.id)>0)
    OR MAX(CASE WHEN s.id IS NOT NULL AND (
        CAST(DATEADD(minute,c.utc_offset_minutes,s.start_at) AS date)<>p.[day]
        OR CAST(DATEADD(minute,c.utc_offset_minutes,DATEADD(nanosecond,-100,s.end_at)) AS date)<>p.[day]
        OR DATEPART(minute,DATEADD(minute,c.utc_offset_minutes,s.start_at))%15<>0
        OR DATEPART(minute,DATEADD(minute,c.utc_offset_minutes,s.end_at))%15<>0
        OR DATEPART(second,s.start_at)<>0 OR DATEPART(nanosecond,s.start_at)<>0
        OR DATEPART(second,s.end_at)<>0 OR DATEPART(nanosecond,s.end_at)<>0
    ) THEN 1 ELSE 0 END)=1;
