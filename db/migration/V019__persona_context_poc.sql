-- Phase PERSONA_CONTEXT_POC — Additive schema only
-- POC-scoped tables for field registry, skill context requirements, and benchmark harness
-- Production chat behavior untouched; only Test Chat + POC benchmark harness use these tables

CREATE TABLE persona_master_documents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  persona_core_version_id UUID NOT NULL UNIQUE,
  content_json JSONB NOT NULL,
  schema_version TEXT NOT NULL DEFAULT '1.0',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (persona_core_version_id) REFERENCES persona_core_versions(id)
);

CREATE TABLE persona_field_registry (
  field_id TEXT PRIMARY KEY,
  path TEXT NOT NULL,
  semantic_type TEXT,
  classification TEXT NOT NULL CHECK (classification IN ('identity', 'behavioral', 'background', 'worldview', 'preference', 'relational', 'narrative', 'visual', 'private', 'intimacy', 'public', 'aspiration', 'decision', 'asset', 'administrative')),
  sensitivity TEXT NOT NULL DEFAULT 'normal' CHECK (sensitivity IN ('normal', 'private', 'restricted', 'admin_only')),
  stability TEXT NOT NULL CHECK (stability IN ('permanent', 'semi_stable', 'changeable', 'narrative', 'session')),
  behavioral_core BOOLEAN NOT NULL DEFAULT false,
  default_load BOOLEAN NOT NULL DEFAULT false,
  allowed_contexts JSONB,
  prohibited_contexts JSONB,
  description TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_persona_field_registry_classification ON persona_field_registry(classification);
CREATE INDEX idx_persona_field_registry_behavioral_core ON persona_field_registry(behavioral_core);

CREATE TABLE skill_context_requirements (
  skill_key TEXT PRIMARY KEY,
  required_fields JSONB,
  recommended_fields JSONB,
  conditional_fields JSONB,
  restricted_fields JSONB,
  purpose TEXT,
  when_to_use TEXT,
  when_not_to_use TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE persona_poc_run (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  persona_id UUID NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  status TEXT NOT NULL DEFAULT 'PLANNING' CHECK (status IN ('PLANNING', 'RUNNING', 'COMPLETED', 'FAILED')),
  variants TEXT[] NOT NULL DEFAULT ARRAY['A', 'B', 'C'],
  FOREIGN KEY (persona_id) REFERENCES personas(id)
);

CREATE TABLE persona_poc_scenario (
  scenario_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  skill_key TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN ('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')),
  user_message TEXT NOT NULL,
  seed_history JSONB,
  seed_memory_facts JSONB,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_persona_poc_scenario_skill ON persona_poc_scenario(skill_key);
CREATE INDEX idx_persona_poc_scenario_category ON persona_poc_scenario(category);

CREATE TABLE persona_poc_execution (
  execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id UUID NOT NULL,
  scenario_id UUID NOT NULL,
  variant TEXT NOT NULL CHECK (variant IN ('A', 'B', 'C')),
  resolved_context_text TEXT,
  persona_tokens INTEGER,
  total_input_tokens INTEGER,
  output_tokens INTEGER,
  latency_ms INTEGER,
  generated_response TEXT,
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
  error TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (run_id) REFERENCES persona_poc_run(id) ON DELETE CASCADE,
  FOREIGN KEY (scenario_id) REFERENCES persona_poc_scenario(scenario_id) ON DELETE CASCADE
);

CREATE INDEX idx_persona_poc_execution_run ON persona_poc_execution(run_id);
CREATE INDEX idx_persona_poc_execution_scenario ON persona_poc_execution(scenario_id);
CREATE INDEX idx_persona_poc_execution_variant ON persona_poc_execution(variant);

CREATE TABLE persona_poc_evaluation (
  evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  execution_id UUID NOT NULL UNIQUE,
  evaluator TEXT NOT NULL,
  fidelity_score SMALLINT CHECK (fidelity_score BETWEEN 0 AND 5),
  factual_accuracy_score SMALLINT CHECK (factual_accuracy_score BETWEEN 0 AND 5),
  skill_adherence_score SMALLINT CHECK (skill_adherence_score BETWEEN 0 AND 5),
  relationship_continuity_score SMALLINT CHECK (relationship_continuity_score BETWEEN 0 AND 5),
  narrative_continuity_score SMALLINT CHECK (narrative_continuity_score BETWEEN 0 AND 5),
  naturalness_score SMALLINT CHECK (naturalness_score BETWEEN 0 AND 5),
  context_precision_score SMALLINT CHECK (context_precision_score BETWEEN 0 AND 5),
  restricted_leakage BOOLEAN DEFAULT false,
  notes TEXT,
  scored_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (execution_id) REFERENCES persona_poc_execution(execution_id) ON DELETE CASCADE
);

CREATE INDEX idx_persona_poc_evaluation_execution ON persona_poc_evaluation(execution_id);
