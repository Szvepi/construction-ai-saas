package com.buildassist.service;

import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.model.EmailDraft;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class DraftQuoteSnapshotService {

    private final DraftQuoteSnapshotCodec codec;

    public DraftQuoteSnapshotService(DraftQuoteSnapshotCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public DraftQuoteSnapshot load(EmailDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return codec.decode(draft.getQuoteSnapshotJson());
    }

    public void save(EmailDraft draft, DraftQuoteSnapshot snapshot) {
        Objects.requireNonNull(draft, "draft");
        if (draft.getLineItemsJson() != null && !draft.getLineItemsJson().isBlank()) {
            throw new IllegalStateException(
                    "A draft cannot persist legacy line items and a structured quote snapshot together.");
        }
        draft.setLineItemsJson(null);
        draft.setQuoteSnapshotJson(codec.encode(codec.seal(snapshot)));
    }

    public DraftQuoteSnapshot seal(DraftQuoteSnapshot snapshot) {
        return codec.seal(snapshot);
    }
}
