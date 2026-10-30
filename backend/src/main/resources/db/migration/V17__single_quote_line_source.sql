ALTER TABLE email_drafts
    ADD CONSTRAINT email_drafts_single_quote_line_source_check
        CHECK (quote_snapshot IS NULL OR line_items IS NULL);
