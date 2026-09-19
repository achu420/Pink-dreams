-- =========================================================================
-- V002: Admin Console Phase 2A — extended user_profiles fields
-- =========================================================================
--
-- IMPORTANT — runtime schema note:
-- As of this migration, this project's actual runtime schema (both the H2
-- test database and, per the current DatabaseFactory.connect() wiring, any
-- real database this app connects to) is created and evolved entirely by
-- Exposed's SchemaUtils.createMissingTablesAndColumns() against the Table
-- objects in DatabaseFactory.kt — NOT by executing the files in this
-- db/migration/ directory. No Flyway (or other migration-runner) dependency
-- or invocation exists anywhere in this codebase.
--
-- This file exists to keep the documented schema history consistent with
-- V001, for anyone who does apply these files by hand to a real Postgres
-- instance. It is not wired into any automatic execution path. If that ever
-- changes, verify this file is compatible with the column types actually
-- declared in DatabaseFactory.kt's UserProfiles object at that time.

ALTER TABLE user_profiles
    ADD COLUMN gender    TEXT,
    ADD COLUMN interest  TEXT CHECK (interest IN ('male', 'female', 'both')),
    ADD COLUMN city      TEXT,
    ADD COLUMN age       INTEGER CHECK (age BETWEEN 18 AND 120);
