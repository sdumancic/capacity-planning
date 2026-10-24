CREATE OR ALTER VIEW dbo.vw_planned_minutes AS
SELECT team_id, member_id, work_order_id, [day], planned_minutes AS minutes
FROM dbo.work_order_time_plan;
