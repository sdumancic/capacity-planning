/* =====================================================================
   Kapacitet iz šihti i absencea – shema (Azure SQL)

   Potvrđeno:
   - pp_team ima kolonu pp_dealer_id
   - pp_team_member.pp_member_id = pp_user.id = ppUserId iz shift mastera (PROVJERITI)
   - work_order_planned_time_v2.member_id = pp_team_member.id (isto vrijedi za work_order_time_plan.member_id)
   - v2 tablica se ne mijenja; booked se čita kroz dbo.vw_planned_minutes (v2 -> v3 na cutoveru)
   - assignment.ppPartnerId mapira na pp_dealer.id (shiftVersion.partnerId = 3523 nije ista stvar)
   - dayOfWeek iz mastera se pri ingestu normalizira na ISO (1 = pon … 7 = ned)
   - year/week iz mastera su ISO week-year / ISO tjedan
   - nema noćnih šihti (sve unutar jednog dana)
   - verzija šihte za dan se razrješava po effective_from (ne po shift_version_id na assignmentu)
   - GREATEST/LEAST zahtijevaju Azure SQL DB / SQL Server 2022+

   Model vremena:
   - trenutci (slotovi, eventi, absence, audit) = DATETIME2(7) u UTC-u, Java Instant
   - poslovni dan = DATE (LocalDate), šihte = TIME (LocalTime) u poslovnoj zoni
   - poslovna zona = jedna po deploymentu/bazi (capacity.business-zone, IANA);
     SQL je ne zna po imenu nego kroz dbo.calendar.utc_offset_minutes (puni aplikacija pri startu)
   - nema noćnih šihti: promjena sata (02:00/03:00) nikad ne pada u radno vrijeme,
     pa je offset u podne točan za cijeli radni dan

   Lokalne kopije namjerno nemaju FK-ove međusobno: eventi stižu out-of-order.
   ===================================================================== */


/* ---------------------------------------------------------------------
   1. Kalendar – statična dimenzija, puni se jednom u migraciji
   --------------------------------------------------------------------- */
CREATE TABLE dbo.calendar (
    [day]             DATE     NOT NULL CONSTRAINT pk_calendar PRIMARY KEY,
    iso_year          SMALLINT NOT NULL,
    iso_week          TINYINT  NOT NULL,
    iso_dow           TINYINT  NOT NULL,           -- 1 = pon … 7 = ned
    utc_offset_minutes SMALLINT NULL               -- offset poslovne zone u podne (CET 60, CEST 120, EET 120, EEST 180);
                                                   -- puni CalendarOffsetInitializer pri startu, NULL = nije napunjeno
    -- praznici/neradni dani su po dealeru: dbo.pp_dealer_working_hours_exception
);

-- postavke koje aplikacija upisuje u bazu (npr. za koju zonu su izračunati offseti)
CREATE TABLE dbo.app_setting (
    [key]      VARCHAR(100)  NOT NULL CONSTRAINT pk_app_setting PRIMARY KEY,
    value      NVARCHAR(400) NOT NULL,
    updated_at DATETIME2(7)  NOT NULL CONSTRAINT df_app_setting_updated DEFAULT SYSUTCDATETIME()
);
CREATE INDEX ix_calendar_week
    ON dbo.calendar (iso_year, iso_week) INCLUDE (iso_dow);

WITH n AS (
    SELECT TOP (DATEDIFF(day, '2020-01-01', '2041-01-01'))
           ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) - 1 AS i
    FROM sys.all_objects a CROSS JOIN sys.all_objects b
),
d AS (SELECT DATEADD(day, i, CAST('2020-01-01' AS date)) AS dt FROM n)
INSERT dbo.calendar ([day], iso_year, iso_week, iso_dow)
SELECT dt,
       YEAR(DATEADD(day, 4 - x.dow, dt)),          -- ISO godina = godina četvrtka tog tjedna
       DATEPART(ISO_WEEK, dt),
       x.dow
FROM d
CROSS APPLY (SELECT (DATEPART(WEEKDAY, dt) + @@DATEFIRST - 2) % 7 + 1 AS dow) x;


/* ---------------------------------------------------------------------
   2. Verzije šihti – čuvaju se SVE verzije (effective_from je vremenski)
   --------------------------------------------------------------------- */
CREATE TABLE dbo.shift_version (
    id             INT           NOT NULL CONSTRAINT pk_shift_version PRIMARY KEY,  -- shiftVersion.id
    shift_id       INT           NOT NULL,
    partner_id     INT           NOT NULL,      -- interni partner mastera (npr. 3523), ne dealer
    pp_partner_id  INT           NULL,          -- = pp_dealer.id
    version_number INT           NOT NULL,
    effective_from DATE          NOT NULL,
    name           NVARCHAR(200) NULL,
    status_code    VARCHAR(30)   NOT NULL,
    synced_at      DATETIME2(7)  NOT NULL CONSTRAINT df_shift_version_synced DEFAULT SYSUTCDATETIME()
);
CREATE INDEX ix_shift_version_resolve
    ON dbo.shift_version (shift_id, effective_from DESC, version_number DESC);
-- pretraga šihti po dealeru (lista šihti dealera, aktualna verzija po šihti)
CREATE INDEX ix_shift_version_dealer
    ON dbo.shift_version (pp_partner_id, shift_id, effective_from DESC, version_number DESC)
    INCLUDE (name, status_code);


/* Radni dani verzije. Upsert verzije = u transakciji DELETE dana/pauza/segmenata
   te verzije + INSERT iz payloada (id-jevi dana se mijenjaju pri izmjeni). */
CREATE TABLE dbo.shift_version_day (
    id               INT      NOT NULL CONSTRAINT pk_shift_version_day PRIMARY KEY,  -- workingDays[].id
    shift_version_id INT      NOT NULL,
    day_of_week      TINYINT  NOT NULL,             -- ISO 1..7
    start_time       TIME(0)  NOT NULL,
    end_time         TIME(0)  NOT NULL,
    working_minutes  SMALLINT NOT NULL,             -- ROUND(totalHours * 60), za validaciju segmenata
    CONSTRAINT ck_shift_version_day_time CHECK (end_time > start_time),
    CONSTRAINT ck_shift_version_day_dow  CHECK (day_of_week BETWEEN 1 AND 7)
);
CREATE INDEX ix_shift_version_day_version
    ON dbo.shift_version_day (shift_version_id, day_of_week);


/* Pauze – kopija izvora, za prikaz na UI-ju. */
CREATE TABLE dbo.shift_version_break (
    id                   INT     NOT NULL CONSTRAINT pk_shift_version_break PRIMARY KEY,  -- breaks[].id
    shift_version_day_id INT     NOT NULL,
    start_time           TIME(0) NOT NULL,
    end_time             TIME(0) NOT NULL,
    CONSTRAINT ck_shift_version_break_time CHECK (end_time > start_time)
);
CREATE INDEX ix_shift_version_break_day
    ON dbo.shift_version_break (shift_version_day_id);


/* Radni segmenti = dan minus pauze. Izvodi se pri ingestu, u istoj transakciji.
   Validacija: SUM(minuta segmenata) po danu mora biti = shift_version_day.working_minutes. */
CREATE TABLE dbo.shift_version_segment (
    shift_version_id INT     NOT NULL,
    day_of_week      TINYINT NOT NULL,
    start_time       TIME(0) NOT NULL,
    end_time         TIME(0) NOT NULL,
    CONSTRAINT pk_shift_version_segment PRIMARY KEY (shift_version_id, day_of_week, start_time),
    CONSTRAINT ck_shift_version_segment_time CHECK (end_time > start_time)
);


/* ---------------------------------------------------------------------
   3. Dodjela šihte zaposleniku po tjednu
   --------------------------------------------------------------------- */
CREATE TABLE dbo.shift_assignment (
    id                 INT          NOT NULL CONSTRAINT pk_shift_assignment PRIMARY KEY,  -- assignment id
    shift_id           INT          NOT NULL,
    shift_version_id   INT          NULL,       -- informativno (dolazi samo u assignment eventu)
    pp_partner_id      INT          NOT NULL,
    pp_user_id         INT          NOT NULL,
    iso_year           SMALLINT     NOT NULL,
    iso_week           TINYINT      NOT NULL,
    enabled            BIT          NOT NULL,   -- false = tombstone, ne brisati redak
    source_modified_at DATETIME2(7) NOT NULL,   -- ignorirati evente starije od ovoga
    synced_at          DATETIME2(7) NOT NULL CONSTRAINT df_shift_assignment_synced DEFAULT SYSUTCDATETIME()
);
CREATE INDEX ix_shift_assignment_partner_week
    ON dbo.shift_assignment (pp_partner_id, iso_year, iso_week)
    INCLUDE (pp_user_id, shift_id)
    WHERE enabled = 1;
-- Opcionalno, ako master garantira jednu šihtu po useru po tjednu:
-- CREATE UNIQUE INDEX ux_shift_assignment_user_week
--     ON dbo.shift_assignment (pp_partner_id, pp_user_id, iso_year, iso_week) WHERE enabled = 1;
-- (out-of-order enable/disable tada ide u retry)


/* ---------------------------------------------------------------------
   4. Absence – UTC interval kako dolazi iz Partner Profilea ("dateStart"/"dateEnd" sa Z)
      update/storno = UPDATE po absence_id (status, interval); nema razbijanja po danima,
      funkcija kapaciteta presijeca interval sa segmentima šihte na vremenskoj osi
      status_code: APPROVED blokira kapacitet i slotove;
                   PENDING se samo prikazuje (pending_absence_minutes, upozorenje na slotu);
                   REJECTED / CANCELLED se ignoriraju (PROVJERITI stvarne kodove u payloadu)
   --------------------------------------------------------------------- */
CREATE TABLE dbo.user_absence (
    absence_id         INT          NOT NULL CONSTRAINT pk_user_absence PRIMARY KEY,
    pp_user_id         INT          NOT NULL,
    pp_partner_id      INT          NULL,       -- dealer; NOT NULL kad payload potvrdi da uvijek dolazi
    absence_type_code  VARCHAR(30)  NULL,
    status_code        VARCHAR(20)  NOT NULL,   -- PENDING / APPROVED / REJECTED / CANCELLED
    start_at           DATETIME2(7) NOT NULL,   -- UTC ("dateStart")
    end_at             DATETIME2(7) NOT NULL,   -- UTC ("dateEnd"), cjelodnevni = lokalna ponoć do ponoći
    enabled            BIT          NOT NULL,
    source_modified_at DATETIME2(7) NOT NULL,
    synced_at          DATETIME2(7) NOT NULL CONSTRAINT df_user_absence_synced DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_user_absence_time CHECK (end_at > start_at)
);
CREATE INDEX ix_user_absence_user_time
    ON dbo.user_absence (pp_user_id, start_at)
    INCLUDE (end_at, status_code)
    WHERE enabled = 1;
-- pretraga absencea po dealeru (lista absencea dealera za period)
CREATE INDEX ix_user_absence_dealer_time
    ON dbo.user_absence (pp_partner_id, start_at)
    INCLUDE (pp_user_id, end_at, absence_type_code, status_code)
    WHERE enabled = 1;


/* ---------------------------------------------------------------------
   4a. Iznimke radnog vremena dealera (workingHoursExceptions iz dealer eventa)
       - array je snapshot: pri obradi eventa DELETE svih redaka dealera + INSERT
       - date: uzeti DATUM iz lokalnog zapisa ("2022-06-16T00:00:00+02:00" -> 2022-06-16),
         NE konvertirati u UTC (dao bi 15.06.)
       - start/end NULL = dealer taj dan ne radi
       - start/end zadani = dealer radi samo u tom prozoru (PROVJERITI semantiku s masterom)
       - u kapacitetu se koriste samo work_activity_type = 'service' i brand IS NULL
   --------------------------------------------------------------------- */
CREATE TABLE dbo.pp_dealer_working_hours_exception (
    id                 INT IDENTITY NOT NULL CONSTRAINT pk_pp_dealer_whe PRIMARY KEY,
    pp_dealer_id       INT           NOT NULL,
    [date]             DATE          NOT NULL,
    start_time         TIME(0)       NULL,
    end_time           TIME(0)       NULL,
    name               NVARCHAR(100) NULL,     -- npr. corpus_christi
    translations       NVARCHAR(MAX) NULL,     -- JSON string kako dolazi
    holiday            NVARCHAR(100) NULL,
    work_activity_type VARCHAR(30)   NULL,     -- service, ...
    brand              VARCHAR(50)   NULL,
    synced_at          DATETIME2(7)  NOT NULL CONSTRAINT df_pp_dealer_whe_synced DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_pp_dealer_whe_time CHECK (
        (start_time IS NULL AND end_time IS NULL)
        OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time))
);
-- jedna iznimka po dealeru/danu/aktivnosti za sve brandove (inače se segmenti dupliraju)
CREATE UNIQUE INDEX ux_pp_dealer_whe_day
    ON dbo.pp_dealer_working_hours_exception (pp_dealer_id, [date], work_activity_type)
    INCLUDE (start_time, end_time)
    WHERE brand IS NULL;


/* ---------------------------------------------------------------------
   4b. Eventi iz hub-calendara (CA = customer appointment, interni sastanci, test drive …)
       - dateStart/dateEnd dolaze u UTC i spremaju se u UTC-u (bez konverzije)
       - event nema dateModified -> redoslijed se štiti Service Bus SequenceNumberom
       - sudionici: samo resource.types[].code = 'EMPLOYEE'; resourceId = pp_user.id
   --------------------------------------------------------------------- */
CREATE TABLE dbo.calendar_event (
    id              INT           NOT NULL CONSTRAINT pk_calendar_event PRIMARY KEY,  -- hub-calendar id
    pp_dealer_id    INT           NOT NULL,     -- = pp_dealer.id, iz payloada
    type_code       VARCHAR(30)   NOT NULL,     -- CA, …
    status_code     VARCHAR(30)   NOT NULL,     -- PENDING, …
    name            NVARCHAR(200) NULL,
    start_at        DATETIME2(7)  NOT NULL,     -- UTC
    end_at          DATETIME2(7)  NOT NULL,     -- UTC
    deleted         BIT           NOT NULL CONSTRAINT df_calendar_event_deleted DEFAULT 0,
    source_sequence BIGINT        NOT NULL,     -- Service Bus SequenceNumber zadnje primijenjene poruke
    synced_at       DATETIME2(7)  NOT NULL CONSTRAINT df_calendar_event_synced DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_calendar_event_time CHECK (end_at > start_at)
);
CREATE INDEX ix_calendar_event_time
    ON dbo.calendar_event (start_at, end_at) INCLUDE (status_code) WHERE deleted = 0;   -- UTC
-- pretraga evenata po dealeru (lista evenata dealera za period)
CREATE INDEX ix_calendar_event_dealer_time
    ON dbo.calendar_event (pp_dealer_id, start_at)
    INCLUDE (end_at, type_code, status_code, name)
    WHERE deleted = 0;
-- fn_user_capacity namjerno NE filtrira po pp_dealer_id: event drugog dealera
-- (user u više dealera) i dalje blokira vrijeme tog usera

-- update eventa = DELETE sudionika po event_id + INSERT
CREATE TABLE dbo.calendar_event_participant (
    event_id    INT NOT NULL,
    pp_user_id  INT NOT NULL,
    CONSTRAINT pk_calendar_event_participant PRIMARY KEY (event_id, pp_user_id)
);
CREATE INDEX ix_calendar_event_participant_user
    ON dbo.calendar_event_participant (pp_user_id) INCLUDE (event_id);


/* ---------------------------------------------------------------------
   5. Planirane minute – jedno mjesto prebacivanja v2 -> v3
      work_order_planned_time_v2 se NE mijenja (deprecated, samo čitanje).
      Release 1: view čita v2. Cutover (merge v3 brancha): isti view se
      redefinira nad dbo.work_order_time_plan (vidi 5b), funkcije ostaju iste.
   --------------------------------------------------------------------- */

-- Release 1: samo indeks za čitanje, bez promjene strukture v2
-- (provjeriti postoji li već ekvivalentan indeks)
CREATE INDEX ix_wopt_team_day
    ON dbo.work_order_planned_time_v2 (team_id, [day]) INCLUDE (member_id, minutes);
GO

CREATE OR ALTER VIEW dbo.vw_planned_minutes
AS
SELECT team_id, member_id, work_order_id, CAST([day] AS date) AS [day], minutes
FROM dbo.work_order_planned_time_v2;
GO

/* Cutover na v3:
CREATE OR ALTER VIEW dbo.vw_planned_minutes
AS
SELECT team_id, member_id, work_order_id, [day], planned_minutes AS minutes
FROM dbo.work_order_time_plan;
GO
*/

-- Postojeće tablice: provjeriti postoje li već ekvivalentni indeksi
-- CREATE INDEX ix_pp_team_dealer   ON dbo.pp_team (pp_dealer_id) INCLUDE (enabled);
-- CREATE INDEX ix_pp_team_member_team ON dbo.pp_team_member (team_id) INCLUDE (pp_member_id, enabled);

-- dbo.team_capacity: ne puni se više; obrisati nakon što se svi čitatelji prebace na funkcije ispod.


/* ---------------------------------------------------------------------
   5b. v3 planiranje i raspoređivanje (branch, aktivno od cutovera)
       work_order_time_plan  = planirano vrijeme naloga po assigneeu i danu
       work_order_time_slot  = zakazani termini member planova
       neraspoređeno = planned_minutes − SUM(slot.minutes), računa se
   --------------------------------------------------------------------- */
CREATE TABLE dbo.work_order_time_plan (
    id              BIGINT IDENTITY NOT NULL CONSTRAINT pk_work_order_time_plan PRIMARY KEY,
    work_order_id   INT             NOT NULL,
    team_id         INT             NOT NULL,   -- pp_team.id
    member_id       INT             NULL,       -- pp_team_member.id; NULL = planirano na tim
    [day]           DATE            NOT NULL,
    planned_minutes INT             NOT NULL CONSTRAINT ck_wotp_minutes CHECK (planned_minutes >= 0),
    version         INT             NOT NULL CONSTRAINT df_wotp_version DEFAULT 0,   -- JPA @Version = expectedVersion
    created_at      DATETIME2(7)    NOT NULL CONSTRAINT df_wotp_created DEFAULT SYSUTCDATETIME(),
    created_by      INT             NULL,
    modified_at     DATETIME2(7)    NOT NULL CONSTRAINT df_wotp_modified DEFAULT SYSUTCDATETIME(),
    modified_by     INT             NULL
);
-- jedan plan po nalogu, assigneeu i danu (NULL member_id = jedna vrijednost -> jedan timski plan)
CREATE UNIQUE INDEX ux_wotp_assignee_day
    ON dbo.work_order_time_plan (work_order_id, team_id, member_id, [day]);
CREATE INDEX ix_wotp_team_day
    ON dbo.work_order_time_plan (team_id, [day]) INCLUDE (member_id, planned_minutes);
CREATE INDEX ix_wotp_member_day
    ON dbo.work_order_time_plan (member_id, [day]) INCLUDE (planned_minutes) WHERE member_id IS NOT NULL;

CREATE TABLE dbo.work_order_time_slot (
    id           BIGINT IDENTITY NOT NULL CONSTRAINT pk_work_order_time_slot PRIMARY KEY,
    time_plan_id BIGINT          NOT NULL CONSTRAINT fk_wots_plan REFERENCES dbo.work_order_time_plan (id),
    start_at     DATETIME2(7)    NOT NULL,   -- UTC
    end_at       DATETIME2(7)    NOT NULL,   -- UTC
    minutes      AS DATEDIFF(minute, start_at, end_at) PERSISTED,
    CONSTRAINT ck_wots_time CHECK (end_at > start_at)
    -- "slot je na poslovnom danu plana" ovisi o zoni: provjerava servis (ZoneId) i noćna provjera (calendar offset)
);
CREATE INDEX ix_wots_plan ON dbo.work_order_time_slot (time_plan_id) INCLUDE (start_at, end_at);

CREATE TABLE dbo.work_order_time_plan_log (
    id                   BIGINT IDENTITY  NOT NULL CONSTRAINT pk_wotp_log PRIMARY KEY,
    correlation_id       UNIQUEIDENTIFIER NOT NULL,
    time_plan_id         BIGINT           NOT NULL,   -- bez FK: log preživljava brisanje i spajanje planova
    related_time_plan_id BIGINT           NULL,
    work_order_id        INT              NOT NULL,
    operation            VARCHAR(30)      NOT NULL,   -- PLAN, SCHEDULE, MOVE_PLAN, MOVE_SLOT, CHANGE_DURATION, DELETE
    from_team_id         INT              NULL,
    from_member_id       INT              NULL,
    to_team_id           INT              NULL,
    to_member_id         INT              NULL,
    minutes              INT              NULL,
    before_state         NVARCHAR(MAX)    NULL CONSTRAINT ck_wotp_log_before CHECK (before_state IS NULL OR ISJSON(before_state) = 1),
    after_state          NVARCHAR(MAX)    NULL CONSTRAINT ck_wotp_log_after  CHECK (after_state  IS NULL OR ISJSON(after_state)  = 1),
    reason               NVARCHAR(500)    NULL,
    initiated_by         VARCHAR(10)      NOT NULL,   -- USER / SYSTEM
    changed_by_user_id   INT              NULL,
    changed_at           DATETIME2(7)     NOT NULL CONSTRAINT df_wotp_log_changed DEFAULT SYSUTCDATETIME()
);
CREATE INDEX ix_wotp_log_plan       ON dbo.work_order_time_plan_log (time_plan_id, changed_at);
CREATE INDEX ix_wotp_log_work_order ON dbo.work_order_time_plan_log (work_order_id, changed_at);

-- hub calendar: stanje sinkronizacije po slotu (bez FK, preživljava brisanje slota dok se event ne obriše)
-- external_id eventa u hubu = 'service-portal:slot:' + slot_id (idempotentni create, search po ključu)
CREATE TABLE dbo.slot_hub_sync (
    slot_id       BIGINT         NOT NULL CONSTRAINT pk_slot_hub_sync PRIMARY KEY,   -- work_order_time_slot.id
    hub_event_id  NVARCHAR(64)   NULL,          -- NULL = u hubu nema eventa (još nije kreiran ili REJECTED)
    status        VARCHAR(10)    NOT NULL,      -- PENDING / SYNCED / REJECTED / FAILED
    error_code    VARCHAR(50)    NULL,          -- HUB_OVERLAP, ... (REJECTED / FAILED)
    error_message NVARCHAR(1000) NULL,
    synced_at     DATETIME2(7)   NULL,          -- zadnja uspješna sinkronizacija, UTC
    updated_at    DATETIME2(7)   NOT NULL CONSTRAINT df_slot_hub_sync_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_slot_hub_sync_status CHECK (status IN ('PENDING','SYNCED','REJECTED','FAILED'))
);
CREATE INDEX ix_slot_hub_sync_rejected ON dbo.slot_hub_sync (status) INCLUDE (slot_id) WHERE status = 'REJECTED';

CREATE TABLE dbo.hub_sync_outbox (
    id              BIGINT IDENTITY NOT NULL CONSTRAINT pk_hub_sync_outbox PRIMARY KEY,
    slot_id         BIGINT          NOT NULL,
    pp_user_id      INT             NOT NULL,   -- osoba slota pri enqueueu: grupiranje i redoslijed po osobi i danu
    [day]           DATE            NOT NULL,   -- poslovni dan slota
    status          VARCHAR(12)     NOT NULL CONSTRAINT df_hub_outbox_status DEFAULT 'PENDING',  -- PENDING / PROCESSING / DONE / DEAD
    attempts        INT             NOT NULL CONSTRAINT df_hub_outbox_attempts DEFAULT 0,
    next_attempt_at DATETIME2(7)    NOT NULL CONSTRAINT df_hub_outbox_next DEFAULT SYSUTCDATETIME(),
    locked_until    DATETIME2(7)    NULL,
    last_error      NVARCHAR(2000)  NULL,
    created_at      DATETIME2(7)    NOT NULL CONSTRAINT df_hub_outbox_created DEFAULT SYSUTCDATETIME(),
    processed_at    DATETIME2(7)    NULL
);
CREATE UNIQUE INDEX ux_hub_outbox_pending ON dbo.hub_sync_outbox (slot_id) WHERE status = 'PENDING';
CREATE INDEX ix_hub_outbox_due ON dbo.hub_sync_outbox (status, next_attempt_at);
CREATE INDEX ix_hub_outbox_user_day ON dbo.hub_sync_outbox (pp_user_id, [day]) WHERE status IN ('PENDING','PROCESSING');
GO


/* ---------------------------------------------------------------------
   pp_user.productivity_percent – produktivnost zaposlenika (Partner Profile, preko eventa pp_user)
   - cijeli broj 0–100; svi postojeći i novi zaposlenici počinju sa 100 (NOT NULL DEFAULT puni postojeće retke)
   - smanjuje samo capacity: FLOOR((shift − blocked) × productivity / 100), po osobi, prije podjele po timovima
   - slotovi, slobodno vrijeme, booked i hub se ne mijenjaju
   - consumer: polje nije u eventu -> vrijednost ostaje; izvan 0–100 ili nije cijeli broj -> dead-letter
   - bez povijesti: promjena vrijednosti preračunava i prošle dane (izvještaji)
   --------------------------------------------------------------------- */
ALTER TABLE dbo.pp_user ADD
    productivity_percent TINYINT NOT NULL
        CONSTRAINT df_pp_user_productivity DEFAULT 100
        CONSTRAINT ck_pp_user_productivity CHECK (productivity_percent BETWEEN 0 AND 100);
GO


/* ---------------------------------------------------------------------
   Terminologija (sve u minutama, po danu):
     shift     = segmenti šihte (bez pauza), unutar radnog vremena dealera
     blocked   = (absence ∪ eventi) unutar šihte; unija, preklapanja se ne broje dvaput
     attended  = shift − blocked      -> prisutno vrijeme bez absencea i evenata
     capacity  = FLOOR(attended × productivity / 100)  -> koliko se smije dodijeliti (stari "planned")
     booked    = minute na radnim nalozima
     available = capacity − booked    -> koliko je još slobodno (isto značenje kao prije)
   ---------------------------------------------------------------------
   6. Kapacitet usera po danu – jedino mjesto s logikom kapaciteta
   --------------------------------------------------------------------- */
CREATE OR ALTER FUNCTION dbo.fn_user_capacity (@dealerId INT, @from DATE, @to DATE)
RETURNS TABLE
AS RETURN
WITH seg_raw AS (
    SELECT sa.pp_user_id,
           c.[day],
           -- NULL offset = kalendar nije inicijaliziran: namjerna greška umjesto tihog kapaciteta 0
           ISNULL(c.utc_offset_minutes,
                  CAST('dbo.calendar.utc_offset_minutes is not initialised' AS INT)) AS utc_offset_minutes,
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
seg AS (                                                         -- segmenti šihte kao UTC intervali: lokalno vrijeme − offset dana
    SELECT pp_user_id, [day],
           DATEADD(minute, -utc_offset_minutes,
                   DATEADD(second, DATEDIFF(second, CAST('00:00' AS time), start_time), CAST([day] AS datetime2(7)))) AS seg_start,
           DATEADD(minute, -utc_offset_minutes,
                   DATEADD(second, DATEDIFF(second, CAST('00:00' AS time), end_time),   CAST([day] AS datetime2(7)))) AS seg_end
    FROM seg_raw
    WHERE end_time > start_time                                  -- segment izvan prozora dealera otpada
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
GO


/* ---------------------------------------------------------------------
   7. Kapacitet za ekran: gusti grid tim × member × dan, timske brojke na svakom retku
      - booked dolazi iz dbo.vw_planned_minutes (v2 do cutovera, work_order_time_plan nakon)
      - tim: booked = SUM svih planiranih minuta tima (timski i member planovi)
      - member: booked = SUM planiranih minuta s njegovim member_id
      - disabled member: shift/blocked/capacity 0, preostali nalozi daju negativan available
      - user u N enabled timova dealera: shift/blocked/attended/capacity se dijele s N
      - capacity već uključuje productivity osobe (fn_user_capacity); attended je prije productivityja
      - available < 0 = overbooking, namjerno bez clampa
   --------------------------------------------------------------------- */
CREATE OR ALTER FUNCTION dbo.fn_team_member_capacity (@dealerId INT, @from DATE, @to DATE)
RETURNS TABLE
AS RETURN
WITH grid AS (
    SELECT t.id AS team_id, tm.id AS member_id, tm.pp_member_id AS pp_user_id,
           tm.enabled AS member_enabled, c.[day],
           -- user u više timova istog dealera: kapacitet se dijeli jednako (kao stari vw_team_availability)
           SUM(CASE WHEN tm.enabled = 1 THEN 1 ELSE 0 END)
               OVER (PARTITION BY tm.pp_member_id, c.[day]) AS team_count
    FROM dbo.pp_team t
    JOIN dbo.pp_team_member tm ON tm.team_id = t.id
    JOIN dbo.calendar c ON c.[day] BETWEEN @from AND @to
    WHERE t.enabled = 1
      AND t.pp_dealer_id = @dealerId
),
member_cap AS (
    SELECT g.team_id, g.member_id, g.[day],
           x.shift_minutes, x.blocked_minutes, x.attended_minutes, x.capacity_minutes, x.pending_absence_minutes,
           ISNULL(uc.productivity_percent, 100) AS productivity_percent,
           SUM(x.shift_minutes)    OVER (PARTITION BY g.team_id, g.[day]) AS team_shift_minutes,
           SUM(x.blocked_minutes)  OVER (PARTITION BY g.team_id, g.[day]) AS team_blocked_minutes,
           SUM(x.attended_minutes) OVER (PARTITION BY g.team_id, g.[day]) AS team_attended_minutes,
           SUM(x.capacity_minutes) OVER (PARTITION BY g.team_id, g.[day]) AS team_capacity_minutes,
           SUM(x.pending_absence_minutes) OVER (PARTITION BY g.team_id, g.[day]) AS team_pending_absence_minutes
    FROM grid g
    LEFT JOIN dbo.fn_user_capacity(@dealerId, @from, @to) uc
      ON uc.pp_user_id = g.pp_user_id AND uc.[day] = g.[day]
    CROSS APPLY (SELECT CASE WHEN g.member_enabled = 1
                             THEN CAST(ROUND(1.0 * ISNULL(uc.shift_minutes,    0) / g.team_count, 0) AS INT) ELSE 0 END AS shift_minutes,
                        CASE WHEN g.member_enabled = 1
                             THEN CAST(ROUND(1.0 * ISNULL(uc.blocked_minutes,  0) / g.team_count, 0) AS INT) ELSE 0 END AS blocked_minutes,
                        CASE WHEN g.member_enabled = 1
                             THEN CAST(ROUND(1.0 * ISNULL(uc.attended_minutes, 0) / g.team_count, 0) AS INT) ELSE 0 END AS attended_minutes,
                        CASE WHEN g.member_enabled = 1
                             THEN CAST(ROUND(1.0 * ISNULL(uc.capacity_minutes, 0) / g.team_count, 0) AS INT) ELSE 0 END AS capacity_minutes,
                        CASE WHEN g.member_enabled = 1
                             THEN CAST(ROUND(1.0 * ISNULL(uc.pending_absence_minutes, 0) / g.team_count, 0) AS INT) ELSE 0 END AS pending_absence_minutes) x
),
booked AS (
    SELECT w.team_id, w.member_id, w.[day], SUM(w.minutes) AS booked_minutes
    FROM dbo.vw_planned_minutes w
    JOIN dbo.pp_team t
      ON t.id = w.team_id AND t.enabled = 1 AND t.pp_dealer_id = @dealerId
    WHERE w.[day] BETWEEN @from AND @to
    GROUP BY w.team_id, w.member_id, w.[day]
),
team_booked AS (
    SELECT team_id, [day], SUM(booked_minutes) AS booked_minutes
    FROM booked
    GROUP BY team_id, [day]
)
SELECT mc.team_id,
       mc.member_id,
       mc.[day],
       mc.team_shift_minutes,
       mc.team_blocked_minutes,
       mc.team_attended_minutes,
       mc.team_capacity_minutes,
       ISNULL(tb.booked_minutes, 0)                             AS team_booked_minutes,
       mc.team_capacity_minutes - ISNULL(tb.booked_minutes, 0)  AS team_available_minutes,
       mc.team_pending_absence_minutes,
       mc.shift_minutes                                         AS member_shift_minutes,
       mc.blocked_minutes                                       AS member_blocked_minutes,
       mc.attended_minutes                                      AS member_attended_minutes,
       mc.productivity_percent                                  AS member_productivity_percent,
       mc.capacity_minutes                                      AS member_capacity_minutes,
       ISNULL(mb.booked_minutes, 0)                             AS member_booked_minutes,
       mc.capacity_minutes - ISNULL(mb.booked_minutes, 0)       AS member_available_minutes,
       mc.pending_absence_minutes                               AS member_pending_absence_minutes
FROM member_cap mc
LEFT JOIN team_booked tb
  ON tb.team_id = mc.team_id AND tb.[day] = mc.[day]
LEFT JOIN booked mb
  ON mb.member_id = mc.member_id AND mb.[day] = mc.[day];
GO


/* ---------------------------------------------------------------------
   8. Pandan tablici team_capacity: member_id NULL = red tima
   --------------------------------------------------------------------- */
CREATE OR ALTER FUNCTION dbo.fn_team_capacity_rows (@dealerId INT, @from DATE, @to DATE)
RETURNS TABLE
AS RETURN
WITH c AS (
    SELECT * FROM dbo.fn_team_member_capacity(@dealerId, @from, @to)
)
SELECT DISTINCT
       team_id,
       CAST(NULL AS INT)        AS member_id,
       [day],
       team_shift_minutes       AS shift_minutes,
       team_blocked_minutes     AS blocked_minutes,
       team_capacity_minutes    AS capacity_minutes,
       team_booked_minutes      AS booked_minutes,
       team_available_minutes   AS available_minutes,
       team_pending_absence_minutes AS pending_absence_minutes,
       team_attended_minutes    AS attended_minutes,          -- dodano na kraj: postojeće mapiranje stupaca ostaje
       CAST(NULL AS TINYINT)    AS productivity_percent       -- tim nema jedan postotak
FROM c
UNION ALL
SELECT team_id,
       member_id,
       [day],
       member_shift_minutes,
       member_blocked_minutes,
       member_capacity_minutes,
       member_booked_minutes,
       member_available_minutes,
       member_pending_absence_minutes,
       member_attended_minutes,
       member_productivity_percent
FROM c;
GO
