-- Time slots at which a custom binding switches itself on and off. Wall times of time_zone (the IANA zone of the
-- browser the slot was saved from, so a repeated slot follows its daylight saving time); NONE is a one-off slot,
-- DAILY/WEEKLY repeat it until repeat_until (inclusive, optional).
CREATE TABLE custom_binding_schedule (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    custom_binding_id UUID        NOT NULL REFERENCES custom_binding (id) ON DELETE CASCADE,
    starts_at         TIMESTAMP   NOT NULL,
    ends_at           TIMESTAMP   NOT NULL,
    repeat            VARCHAR(10) NOT NULL DEFAULT 'NONE',
    repeat_until      DATE,
    time_zone         VARCHAR(64) NOT NULL DEFAULT 'Europe/Paris',
    paused            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_custom_binding_schedule_binding ON custom_binding_schedule (custom_binding_id);

CREATE TABLE custom_binding_schedule_day (
    schedule_id UUID        NOT NULL REFERENCES custom_binding_schedule (id) ON DELETE CASCADE,
    day_of_week VARCHAR(10) NOT NULL,
    PRIMARY KEY (schedule_id, day_of_week)
);

-- Occurrences are instants (TIMESTAMPTZ): read back as written whatever the JVM's zone, so the scheduler
-- recognises the occurrence it already applied.
-- Occurrences a streamer refused (stopped by hand): not re-activated. Purged once the occurrence is over.
CREATE TABLE custom_binding_schedule_refusal (
    schedule_id      UUID      NOT NULL REFERENCES custom_binding_schedule (id) ON DELETE CASCADE,
    occurrence_start TIMESTAMPTZ NOT NULL,
    occurrence_end   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (schedule_id, occurrence_start)
);

ALTER TABLE custom_binding_activation
    ADD COLUMN origin           VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN schedule_id      UUID REFERENCES custom_binding_schedule (id) ON DELETE SET NULL,
    ADD COLUMN occurrence_start TIMESTAMPTZ,
    ADD COLUMN occurrence_end   TIMESTAMPTZ;
