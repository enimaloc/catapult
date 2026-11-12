-- Each cached topic's severity, as DTDD's members voted it (levels, counts, percents, total, the reviewers' level),
-- by topic name. NULL: fetched before the severities were kept; a topic without levels is absent.
ALTER TABLE dtdd_topics_cache ADD COLUMN severities JSONB;
