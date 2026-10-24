-- Standalone fixture contracts for upstream local copies; deliberately no mutual foreign keys.
CREATE TABLE dbo.pp_dealer(id INT NOT NULL PRIMARY KEY, name NVARCHAR(200) NOT NULL);
CREATE TABLE dbo.pp_user(id INT NOT NULL PRIMARY KEY, name NVARCHAR(200) NOT NULL);
CREATE TABLE dbo.pp_team(id INT NOT NULL PRIMARY KEY, pp_dealer_id INT NOT NULL, name NVARCHAR(200) NOT NULL, enabled BIT NOT NULL);
CREATE TABLE dbo.pp_team_member(id INT NOT NULL PRIMARY KEY, team_id INT NOT NULL, pp_member_id INT NOT NULL, enabled BIT NOT NULL);
CREATE TABLE dbo.work_order(id INT NOT NULL PRIMARY KEY, dealer_id INT NOT NULL, title NVARCHAR(200) NOT NULL, dropoff_at DATETIME2(7) NOT NULL, pickup_at DATETIME2(7) NOT NULL);
-- Empty legacy read prerequisite; the cutover view uses v3 below.
CREATE TABLE dbo.work_order_planned_time_v2(team_id INT NOT NULL, member_id INT NULL, work_order_id INT NOT NULL, [day] DATE NOT NULL, minutes INT NOT NULL);
