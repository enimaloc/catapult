-- The least severe level of its DTDD topic that turns the TW on (one of the topic's severity labels, as DTDD names
-- them); NULL: any level, as before. Below it, the topic doesn't turn the TW on — unless DTDD's members cast too few
-- level votes to tell (dtdd.severity.min-votes) and its reviewers gave none.
ALTER TABLE tw_dtdd_topic_mapping ADD COLUMN min_severity VARCHAR(40);

-- Only where the mildest level is harmless to show (mentioned, implied, fake: a phobia's subject only when it isn't
-- there for real); never for self-harm, sexual violence, child abuse nor flashing lights.
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Depicted' WHERE lower(dtdd_topic_name) = 'there''s blood/gore';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Clear' WHERE lower(dtdd_topic_name) = 'there''s audio gore';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Heard' WHERE lower(dtdd_topic_name) = 'someone vomits';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Startling' WHERE lower(dtdd_topic_name) = 'there are jump scares';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Startling' WHERE lower(dtdd_topic_name) = 'there are sudden loud noises';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Mild' WHERE lower(dtdd_topic_name) = 'there''s screaming';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Crude' WHERE lower(dtdd_topic_name) = 'there is obscene language/gestures';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Clear' WHERE lower(dtdd_topic_name) = 'shaky cam is used';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Depicted' WHERE lower(dtdd_topic_name) = 'needles/syringes are used';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Drunk' WHERE lower(dtdd_topic_name) = 'alcohol abuse';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Used' WHERE lower(dtdd_topic_name) = 'someone uses drugs';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Harmless' WHERE lower(dtdd_topic_name) = 'there are snakes';
UPDATE tw_dtdd_topic_mapping SET min_severity = 'Harmless' WHERE lower(dtdd_topic_name) = 'there are spiders';
