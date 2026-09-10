-- Seeds tw_steam_keyword, which has been empty since V54 wiped tw_definition
-- (cascading away the V51 keywords) and nothing reseeded it since.
--
-- Every phrase below was verified as a literal, case-insensitive substring
-- match against real Steam store content_descriptor_notes text (dry run
-- against a ~183k-app Steam catalog dump) before being added here — plain
-- bigrams built from tokenized/stopword-stripped text were checked one by
-- one, since TwResolverService.suggest() does a raw String.contains() and
-- several "obvious" candidates (e.g. "drug alcohol", "baby stillborn") never
-- appear as contiguous text in practice.
--
-- Deliberately excluded (no reliable signal found in real notes text):
-- live_violence_event, sensory_audio, emotional_distress, accident,
-- animal_fear, abandonment, symbolic_destruction. These keep zero keywords
-- until someone manually curates them.

INSERT INTO tw_steam_keyword (tw_id, keyword) VALUES
  ('mature_content', 'mature content'),
  ('mature_content', 'mature themes'),
  ('mature_content', 'general mature content'),
  ('mature_content', 'mature audiences'),

  ('sexual_content', 'sexual content'),
  ('sexual_content', 'partial nudity'),
  ('sexual_content', 'full nudity'),
  ('sexual_content', 'sexual themes'),

  ('sexual_content_explicit', 'explicit sexual'),
  ('sexual_content_explicit', 'adult content'),
  ('sexual_content_explicit', 'adult themes'),
  ('sexual_content_explicit', 'adults only'),

  ('sexual_assault', 'sexual assault'),
  ('sexual_assault', 'non-consensual'),
  ('sexual_assault', 'sexual violence'),

  ('child_sexualization', 'involving minors'),

  ('violence', 'fantasy violence'),
  ('violence', 'cartoon violence'),

  ('graphic_violence', 'graphic violence'),
  ('graphic_violence', 'gore'),
  ('graphic_violence', 'blood'),

  ('physical_injury', 'injury'),
  ('physical_injury', 'injuries'),
  ('physical_injury', 'broken bone'),
  ('physical_injury', 'broken bones'),

  ('mutilation', 'dismemberment'),
  ('mutilation', 'genital mutilation'),
  ('mutilation', 'mutilation'),

  ('torture', 'torture'),

  ('death_human', 'death of a'),
  ('death_human', 'human death'),

  ('death_child', 'child death'),
  ('death_child', 'death of a child'),

  ('death_animal', 'dead animal'),
  ('death_animal', 'animal death'),

  ('asphyxiation', 'choking'),
  ('asphyxiation', 'drowning'),
  ('asphyxiation', 'buried alive'),

  ('mental_health', 'mental illness'),
  ('mental_health', 'ptsd'),
  ('mental_health', 'seizure'),

  ('suicide_self_harm', 'self harm'),
  ('suicide_self_harm', 'suicide'),

  ('eating_disorder', 'eating disorder'),
  ('eating_disorder', 'body image'),

  ('medical_condition', 'heart attack'),
  ('medical_condition', 'chronic illness'),

  ('medical_procedure', 'hospital'),
  ('medical_procedure', 'syringes'),
  ('medical_procedure', 'medical procedure'),

  ('substance_abuse', 'alcohol abuse'),
  ('substance_abuse', 'alcohol use'),
  ('substance_abuse', 'drug use'),
  ('substance_abuse', 'drug and alcohol'),

  ('domestic_abuse', 'domestic violence'),
  ('domestic_abuse', 'domestic abuse'),

  ('child_abuse', 'child abuse'),

  ('bullying', 'bullying'),
  ('bullying', 'harassment'),
  ('bullying', 'gaslighting'),

  ('kidnapping', 'kidnapping'),
  ('kidnapping', 'abduction'),
  ('kidnapping', 'hostage'),

  ('discrimination', 'racism'),
  ('discrimination', 'hate speech'),

  ('sexual_discrimination', 'deadnaming'),
  ('sexual_discrimination', 'outed'),
  ('sexual_discrimination', 'transphobia'),
  ('sexual_discrimination', 'gender discrimination'),

  ('social_issue', 'homeless'),
  ('social_issue', 'homelessness'),

  ('sexual_behavior', 'sexual acts'),
  ('sexual_behavior', 'consensual sex'),

  ('horror', 'psychological horror'),
  ('horror', 'horror elements'),
  ('horror', 'horror themes'),

  ('body_horror', 'body horror'),

  ('supernatural', 'demons'),
  ('supernatural', 'ghosts'),
  ('supernatural', 'possession'),
  ('supernatural', 'hell'),

  ('jump_scare', 'jump scare'),
  ('jump_scare', 'jump scares'),

  ('phobia_spiders', 'spiders'),
  ('phobia_spiders', 'arachnophobia'),

  ('phobia_insects', 'insects'),
  ('phobia_insects', 'bugs'),

  ('phobia_snakes', 'snakes'),

  ('phobia_clowns', 'clowns'),

  ('phobia_water', 'drowning'),
  ('phobia_water', 'underwater'),

  ('phobia_confined_spaces', 'claustrophobic'),
  ('phobia_confined_spaces', 'claustrophobia'),

  ('phobia_trypophobia', 'trypophobia'),

  ('sensory_flashing', 'flashing lights'),
  ('sensory_flashing', 'stroboscopic'),

  ('sensory_loud_noises', 'loud noises'),

  ('sensory_motion', 'motion sickness'),
  ('sensory_motion', 'motion effects'),

  ('natural_disaster', 'earthquake'),
  ('natural_disaster', 'nuclear explosion'),
  ('natural_disaster', 'disaster'),

  ('gross_out', 'bodily fluids'),
  ('gross_out', 'farting'),
  ('gross_out', 'vomiting'),

  ('religion', 'religious'),
  ('religion', 'religion'),

  ('meta_content', 'fourth wall'),
  ('meta_content', 'meta'),

  ('stage_effects', 'theatrical'),

  ('language_profanity', 'profanity'),
  ('language_profanity', 'obscene language'),
  ('language_profanity', 'strong language'),

  ('animal_abuse', 'animal cruelty');
