-- The rest of the features of a photo that the recommended bundle combination keeps (specification 4-3, 4-4, 4-5): the
-- state and the answer of the AI analysis, and the quality that the server calculates from the image. Every column is
-- optional. A photo without a value was uploaded before the value could be made, or its image could not be read, and
-- the one who reads the value decides what that means. The AI columns are only a place for now; nothing fills them
-- until the analysis is made.
ALTER TABLE photo_reco_feature
    ADD COLUMN ai_status VARCHAR(16),
    ADD COLUMN ai_model_version VARCHAR(64),
    ADD COLUMN ai_raw JSONB,
    ADD COLUMN quality VARCHAR(8),
    ADD COLUMN analyzed_at TIMESTAMP WITH TIME ZONE;

-- No value in ai_status means the analysis was never asked for.
ALTER TABLE photo_reco_feature
    ADD CONSTRAINT ck_photo_reco_feature_ai_status
        CHECK (ai_status IS NULL OR ai_status IN ('PENDING', 'DONE', 'FAILED')),
    ADD CONSTRAINT ck_photo_reco_feature_quality
        CHECK (quality IS NULL OR quality IN ('GOOD', 'NORMAL', 'LOW'));
