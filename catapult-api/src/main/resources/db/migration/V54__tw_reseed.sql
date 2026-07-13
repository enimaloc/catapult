DELETE FROM tw_definition;

INSERT INTO tw_definition (id, label, description, sort_order)
VALUES

-- =========================================
-- GLOBAL / META
-- =========================================
('mature_content', 'Mature content', 'General mature content descriptor', 1),

-- =========================================
-- SEXUAL CONTENT
-- =========================================
('sexual_content', 'Sexual content', 'Nudity, sexual situations, suggestive content', 10),
('sexual_content_explicit', 'Explicit sexual content', 'Explicit adult sexual content only', 11),
('sexual_assault', 'Sexual assault', 'Sexual violence or coercion', 12),
('child_sexualization', 'Child sexualization', 'Sexual content involving minors or grooming', 13),

-- =========================================
-- VIOLENCE / DEATH
-- =========================================
('violence', 'Violence', 'Physical violence (non-graphic)', 20),
('graphic_violence', 'Graphic violence', 'Gore and explicit injury', 21),
('physical_injury', 'Physical injury', 'Non-lethal injuries', 22),
('mutilation', 'Mutilation', 'Severe body dismemberment or mutilation', 23),
('torture', 'Torture', 'Severe physical abuse and torture', 24),

('death_human', 'Human death', 'Death of any human character', 30),
('death_child', 'Child death', 'Death of children or unborn', 31),
('death_animal', 'Animal death', 'Death of animals', 32),

('asphyxiation', 'Asphyxiation / choking', 'Strangulation, suffocation, drowning breath restriction', 33),

-- =========================================
-- PSYCHOLOGICAL / MENTAL
-- =========================================
('mental_health', 'Mental health', 'Mental illness depiction', 40),
('emotional_distress', 'Emotional distress', 'Grief, sadness, emotional trauma', 41),
('suicide_self_harm', 'Suicide / self-harm', 'Suicide and self-injury content', 42),
('eating_disorder', 'Eating disorder', 'Body image and eating disorders', 43),

-- =========================================
-- MEDICAL
-- =========================================
('medical_condition', 'Medical condition', 'Diseases and chronic illness', 50),
('medical_procedure', 'Medical procedure', 'Medical treatments and interventions', 51),

-- =========================================
-- SUBSTANCES
-- =========================================
('substance_abuse', 'Substance abuse', 'Alcohol, drugs, addiction', 60),

-- =========================================
-- SOCIAL / ABUSE
-- =========================================
('domestic_abuse', 'Domestic abuse', 'Family or partner violence', 70),
('child_abuse', 'Child abuse', 'Abuse involving children', 71),
('bullying', 'Bullying', 'Harassment, manipulation, gaslighting', 72),
('abandonment', 'Abandonment', 'Being left alone or rejected', 73),
('kidnapping', 'Kidnapping', 'Abduction and hostage situations', 74),

-- =========================================
-- DISCRIMINATION
-- =========================================
('discrimination', 'Discrimination', 'Racism, ableism, hate speech', 80),
('sexual_discrimination', 'Gender/LGBT discrimination', 'Transphobia, misgendering, outing', 81),
('social_issue', 'Social issue', 'Homelessness, incarceration, societal issues', 82),

-- =========================================
-- SEXUAL BEHAVIOR (NON-VIOLENT)
-- =========================================
('sexual_behavior', 'Sexual behavior', 'Non-violent sexual acts and nudity context', 90),

-- =========================================
-- HORROR / SUPERNATURAL
-- =========================================
('horror', 'Horror', 'General horror themes', 100),
('body_horror', 'Body horror', 'Physical transformation horror', 101),
('supernatural', 'Supernatural', 'Ghosts, demons, possession', 102),
('jump_scare', 'Jump scare', 'Sudden scare effects', 103),

-- =========================================
-- PHOBIAS
-- =========================================
('phobia_spiders', 'Spiders', 'Arachnophobia triggers', 110),
('phobia_insects', 'Insects', 'Insect-related fear triggers', 111),
('phobia_snakes', 'Snakes', 'Snake-related fear triggers', 112),
('phobia_clowns', 'Clowns', 'Clown-related fear triggers', 113),
('phobia_water', 'Water / drowning', 'Deep water, drowning anxiety', 114),
('phobia_confined_spaces', 'Confined spaces', 'Claustrophobia triggers', 115),
('phobia_trypophobia', 'Trypophobia', 'Pattern hole triggers', 116),
('animal_fear', 'Dangerous animals', 'Sharks, crocodiles, predators', 117),

-- =========================================
-- SENSORIAL
-- =========================================
('sensory_flashing', 'Flashing lights', 'Stroboscopic light effects', 120),
('sensory_loud_noises', 'Loud noises', 'Sudden loud sound triggers', 121),
('sensory_motion', 'Motion effects', 'Shaky cam, motion sickness triggers', 122),
('sensory_audio', 'Audio sensitivity', 'Misophonia and disturbing audio', 123),

-- =========================================
-- ACCIDENTS / DISASTERS
-- =========================================
('accident', 'Accidents', 'Car crashes, collisions, accidents', 130),
('natural_disaster', 'Natural disasters', 'Earthquakes, tsunamis, explosions', 131),

-- =========================================
-- GROSS OUT
-- =========================================
('gross_out', 'Gross out', 'Vomiting, excretion, bodily fluids', 140),

-- =========================================
-- RELIGION / META
-- =========================================
('religion', 'Religion', 'Religious themes', 150),
('meta_content', 'Meta content', 'Fourth wall breaks, meta jokes', 151),

-- =========================================
-- PERFORMANCE / SPECIAL CASES
-- =========================================
('stage_effects', 'Stage / performance effects', 'Audience interaction, theatrical effects', 160),
('live_violence_event', 'Live violence event', 'Real-time violent events (rare)', 161),
('symbolic_destruction', 'Symbolic destruction', 'Important object destruction', 162),

-- =========================================
-- LANGUAGE / SOCIAL BEHAVIOR
-- =========================================
('language_profanity', 'Profanity', 'Strong language and gestures', 170),

-- =========================================
-- MISC
-- =========================================
('animal_abuse', 'Animal abuse', 'Cruelty toward animals', 180);