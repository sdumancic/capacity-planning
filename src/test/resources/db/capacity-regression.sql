-- sqlcmd -b -i src/test/resources/db/capacity-regression.sql after migrations/startup.
-- Explicit UTC offsets isolate SQL semantics from the configured business zone.
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;
SET XACT_ABORT ON;
BEGIN TRANSACTION;
BEGIN TRY
 INSERT dbo.pp_dealer(id,name) VALUES(-900,N'Regression'),(-999,N'Other dealer');
 INSERT dbo.pp_user(id,name,productivity_percent) VALUES(-900,N'Regression',80);
 INSERT dbo.pp_team(id,pp_dealer_id,name,enabled) VALUES(-901,-900,N'One',1),(-902,-900,N'Two',1),(-903,-900,N'Three',1);
 INSERT dbo.pp_team_member(id,team_id,pp_member_id,enabled) VALUES(-901,-901,-900,1),(-902,-902,-900,1),(-903,-903,-900,0);
 UPDATE dbo.calendar SET utc_offset_minutes=0 WHERE [day] BETWEEN '20261023' AND '20261026';
 INSERT dbo.shift_version(id,shift_id,partner_id,pp_partner_id,version_number,effective_from,status_code)
 VALUES(-901,-900,-900,-900,1,'20260101','ACTIVE'),(-902,-900,-900,-900,2,'20261023','ACTIVE');
 INSERT dbo.shift_version_segment(shift_version_id,day_of_week,start_time,end_time)
 VALUES(-901,5,'07:00','08:00'),(-902,5,'07:00','07:31'),(-902,1,'07:00','07:31');
 INSERT dbo.shift_assignment(id,shift_id,shift_version_id,pp_partner_id,pp_user_id,iso_year,iso_week,enabled,source_modified_at)
 VALUES(-901,-900,-901,-900,-900,2026,43,1,'20261001'),(-902,-900,-901,-900,-900,2026,44,1,'20261001');
 INSERT dbo.user_absence(absence_id,pp_user_id,status_code,start_at,end_at,enabled,source_modified_at)
 VALUES(-901,-900,'APPROVED','20261023 07:00','20261023 07:15',1,'20261001'),
 (-902,-900,'PENDING','20261023 07:00','20261023 07:31',1,'20261001'),
 (-903,-900,'APPROVED','20261023 07:00','20261023 07:31',0,'20261001');
 INSERT dbo.calendar_event(id,pp_dealer_id,type_code,status_code,start_at,end_at,deleted,source_sequence)
 VALUES(-901,-999,'MEETING','PENDING','20261023 07:10','20261023 07:20',0,1),
 (-902,-999,'MEETING','PENDING','20261023 07:20','20261023 07:31',1,1),
 (-903,-999,'MEETING','CANCELLED','20261023 07:20','20261023 07:31',0,1),
 (-904,-999,'MEETING','PENDING','20261023 07:20','20261023 07:31',0,1);
 INSERT dbo.calendar_event_participant(event_id,pp_user_id) VALUES(-901,-900),(-902,-900),(-903,-900);
 -- Other dealer's participant blocks; unassociated/deleted/cancelled events do not.
 IF NOT EXISTS(SELECT 1 FROM dbo.fn_user_capacity(-900,'20261023','20261023')
   WHERE shift_minutes=31 AND blocked_minutes=20 AND attended_minutes=11 AND capacity_minutes=8 AND pending_absence_minutes=11)
 THROW 51001,'Effective version, event union, pending or productivity regression',1;
 -- Identical enabled assignments must not duplicate blocked minutes.
 INSERT dbo.shift_assignment(id,shift_id,shift_version_id,pp_partner_id,pp_user_id,iso_year,iso_week,enabled,source_modified_at)
 VALUES(-903,-900,-901,-900,-900,2026,43,1,'20261001');
 IF NOT EXISTS(SELECT 1 FROM dbo.fn_user_capacity(-900,'20261023','20261023')
   WHERE shift_minutes=31 AND blocked_minutes=20 AND attended_minutes=11 AND capacity_minutes=8 AND pending_absence_minutes=11)
 THROW 51009,'Duplicate enabled assignments counted twice',1;
 -- A different shift contributes a contained interval, preserving the same union.
 INSERT dbo.shift_version(id,shift_id,partner_id,pp_partner_id,version_number,effective_from,status_code)
 VALUES(-903,-901,-900,-900,1,'20260101','ACTIVE');
 INSERT dbo.shift_version_segment(shift_version_id,day_of_week,start_time,end_time)
 VALUES(-903,5,'07:05','07:25');
 INSERT dbo.shift_assignment(id,shift_id,shift_version_id,pp_partner_id,pp_user_id,iso_year,iso_week,enabled,source_modified_at)
 VALUES(-904,-901,-903,-900,-900,2026,43,1,'20261001');
 IF NOT EXISTS(SELECT 1 FROM dbo.fn_user_capacity(-900,'20261023','20261023')
   WHERE shift_minutes=31 AND blocked_minutes=20 AND attended_minutes=11 AND capacity_minutes=8 AND pending_absence_minutes=11)
 THROW 51010,'Overlapping distinct assignments counted twice',1;
 -- Supplied independent ROUND is intentional: two 31/2 shares total 32, not 31.
 IF (SELECT SUM(member_shift_minutes) FROM dbo.fn_team_member_capacity(-900,'20261023','20261023'))<>32
 THROW 51002,'Supplied per-membership ROUND regression',1;
 IF NOT EXISTS(SELECT 1 FROM dbo.fn_team_member_capacity(-900,'20261023','20261023') WHERE member_id=-903 AND member_capacity_minutes=0)
 THROW 51003,'Disabled membership gained capacity',1;
 UPDATE dbo.pp_team_member SET enabled=1 WHERE id=-903;
 IF (SELECT SUM(member_capacity_minutes) FROM dbo.fn_team_member_capacity(-900,'20261023','20261023'))<>9
 THROW 51004,'Three memberships must ROUND capacity 8/3 to 3 each',1;
 INSERT dbo.work_order_time_plan(work_order_id,team_id,member_id,[day],planned_minutes) VALUES(-900,-901,-901,'20261023',17);
 IF NOT EXISTS(SELECT 1 FROM dbo.fn_team_capacity_rows(-900,'20261023','20261023') WHERE team_id=-901 AND member_id IS NULL AND booked_minutes=17 AND available_minutes=-14)
 THROW 51005,'v3 cutover/booked/negative availability regression',1;
 INSERT dbo.pp_dealer_working_hours_exception(pp_dealer_id,[date],start_time,end_time,work_activity_type)
 VALUES(-900,'20261023','07:15','07:30','service');
 IF NOT EXISTS(SELECT 1 FROM dbo.fn_user_capacity(-900,'20261023','20261023') WHERE shift_minutes=15 AND blocked_minutes=5 AND attended_minutes=10 AND capacity_minutes=8 AND pending_absence_minutes=10)
 THROW 51006,'Dealer exception window regression',1;
 UPDATE dbo.pp_dealer_working_hours_exception SET start_time=NULL,end_time=NULL WHERE pp_dealer_id=-900;
 IF EXISTS(SELECT 1 FROM dbo.fn_user_capacity(-900,'20261023','20261023'))
 THROW 51007,'Closed dealer gained capacity',1;
 UPDATE dbo.shift_assignment SET enabled=0 WHERE id=-902;
 IF EXISTS(SELECT 1 FROM dbo.fn_user_capacity(-900,'20261026','20261026'))
 THROW 51008,'Assignment tombstone gained capacity',1;
 ROLLBACK TRANSACTION;
 PRINT 'Capacity regression assertions passed';
END TRY
BEGIN CATCH
 IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
 THROW;
END CATCH;

GO
-- Separate transaction: a deliberate conversion error can doom a transaction with XACT_ABORT ON.
BEGIN TRANSACTION;
BEGIN TRY
 INSERT dbo.shift_version(id,shift_id,partner_id,version_number,effective_from,status_code)
 VALUES(-990,-990,-990,1,'20260101','ACTIVE');
 INSERT dbo.shift_version_segment(shift_version_id,day_of_week,start_time,end_time)
 VALUES(-990,5,'07:00','08:00');
 INSERT dbo.shift_assignment(id,shift_id,pp_partner_id,pp_user_id,iso_year,iso_week,enabled,source_modified_at)
 VALUES(-990,-990,-990,-990,2026,43,1,'20261001');
 UPDATE dbo.calendar SET utc_offset_minutes=NULL WHERE [day]='20261023';
 DECLARE @minutes INT;
 SELECT @minutes=SUM(shift_minutes) FROM dbo.fn_user_capacity(-990,'20261023','20261023');
 ROLLBACK TRANSACTION;
 THROW 51011,'NULL calendar offset must fail explicitly',1;
END TRY
BEGIN CATCH
 DECLARE @error INT=ERROR_NUMBER();
 IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
 IF @error<>245 THROW;
 PRINT 'NULL calendar offset fails explicitly as expected';
END CATCH;
