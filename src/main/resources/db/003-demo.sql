-- Standalone demo fixtures for the upstream contracts; no production integration implied.
INSERT dbo.pp_dealer(id,name) VALUES(1,N'Demo workshop');
INSERT dbo.pp_team(id,pp_dealer_id,name,enabled) VALUES(53,1,N'Toyota 1',1),(61,1,N'Toyota 2',1);
INSERT dbo.pp_user(id,name,productivity_percent) VALUES(452,N'Ana',100),(460,N'Marko',80),(470,N'Ivan',100);
INSERT dbo.pp_team_member(id,team_id,pp_member_id,enabled) VALUES(452,53,452,1),(460,53,460,1),(470,61,470,1);
INSERT dbo.work_order(id,dealer_id,title,dropoff_at,pickup_at) VALUES
(34084,1,N'Brake inspection','2026-10-19T05:00:00','2026-10-30T16:00:00'),
(1511,1,N'Annual service','2026-10-19T05:00:00','2026-10-30T16:00:00'),
(1505,1,N'Tyre replacement','2026-10-19T05:00:00','2026-10-30T16:00:00'),
(1506,1,N'Engine diagnostics','2026-10-19T05:00:00','2026-10-30T16:00:00');
INSERT dbo.shift_version(id,shift_id,partner_id,pp_partner_id,version_number,effective_from,name,status_code) VALUES
(101,10,3523,1,1,'20261019',N'Service morning','ACTIVE'),
(102,10,3523,1,2,'20261026',N'Service winter morning','ACTIVE');
INSERT dbo.shift_version_day(id,shift_version_id,day_of_week,start_time,end_time,working_minutes)
SELECT v.id*10+d.dow,v.id,d.dow,'07:00','16:00',480
FROM (VALUES(101),(102)) v(id) CROSS JOIN (VALUES(1),(2),(3),(4),(5)) d(dow);
INSERT dbo.shift_version_break(id,shift_version_day_id,start_time,end_time)
SELECT id,id,'12:00','13:00' FROM dbo.shift_version_day;
INSERT dbo.shift_version_segment(shift_version_id,day_of_week,start_time,end_time)
SELECT v.id,d.dow,s.a,s.b FROM (VALUES(101),(102)) v(id)
CROSS JOIN (VALUES(1),(2),(3),(4),(5)) d(dow)
CROSS JOIN (VALUES(CAST('07:00' AS time),CAST('12:00' AS time)),(CAST('13:00' AS time),CAST('16:00' AS time))) s(a,b);
-- Assignment version remains informational: version 102 resolves by effective_from in week 44.
INSERT dbo.shift_assignment(id,shift_id,shift_version_id,pp_partner_id,pp_user_id,iso_year,iso_week,enabled,source_modified_at)
SELECT u.id*100+w.week,10,101,1,u.id,2026,w.week,1,'20261001'
FROM dbo.pp_user u CROSS JOIN (VALUES(43),(44)) w(week);
INSERT dbo.user_absence(absence_id,pp_user_id,pp_partner_id,status_code,start_at,end_at,enabled,source_modified_at) VALUES
(1,452,1,'APPROVED','2026-10-23T07:00:00','2026-10-23T08:00:00',1,'20261001'),
(2,452,1,'PENDING','2026-10-23T07:30:00','2026-10-23T09:00:00',1,'20261001'),
(3,460,1,'PENDING','2026-10-23T11:00:00','2026-10-23T12:00:00',1,'20261001');
INSERT dbo.calendar_event(id,pp_dealer_id,type_code,status_code,name,start_at,end_at,source_sequence)
VALUES(1,1,'MEETING','PENDING',N'Workshop meeting','2026-10-23T07:30:00','2026-10-23T08:30:00',1);
INSERT dbo.calendar_event_participant(event_id,pp_user_id) VALUES(1,452);
INSERT dbo.pp_dealer_working_hours_exception(pp_dealer_id,[date],start_time,end_time,name,work_activity_type)
VALUES(1,'20261029','08:00','15:00',N'Short opening','service');
INSERT dbo.work_order_time_plan(work_order_id,team_id,member_id,day,planned_minutes)
VALUES(34084,53,452,'20261023',180),(1505,53,NULL,'20261023',90),(1511,61,470,'20261023',300),(1506,53,460,'20261026',97);
