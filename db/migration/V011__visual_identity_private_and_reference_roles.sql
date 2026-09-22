-- Task 24 / Persona Visual Identity: private guide column + expanded reference roles.

ALTER TABLE persona_visual_versions
    ADD COLUMN IF NOT EXISTS private_guide TEXT NOT NULL DEFAULT '{}';

-- Replace role CHECK to include product standard slots + PRIVATE/OTHER (keep legacy).
ALTER TABLE persona_visual_reference_images
    DROP CONSTRAINT IF EXISTS persona_visual_reference_images_role_check;

ALTER TABLE persona_visual_reference_images
    ADD CONSTRAINT persona_visual_reference_images_role_check
    CHECK (role IN (
        'FRONT', 'FACE_CLOSE', 'LEFT_PROFILE', 'RIGHT_PROFILE', 'BACK',
        'PRIVATE', 'OTHER',
        'FACE', 'FULL_BODY', 'BODY', 'HAIR', 'WARDROBE', 'STYLE', 'GENERAL_IDENTITY'
    ));

-- Include private_guide in published/archived immutability.
DROP TRIGGER IF EXISTS trg_visual_version_immutable ON persona_visual_versions;
CREATE TRIGGER trg_visual_version_immutable
    BEFORE UPDATE OF physical_guide, style_constraints, private_guide, status, changelog_note
    ON persona_visual_versions
    FOR EACH ROW
    EXECUTE FUNCTION chk_visual_version_immutable();
