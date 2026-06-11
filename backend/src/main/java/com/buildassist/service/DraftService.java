package com.buildassist.service;

import com.buildassist.dto.DraftDtos.GenerateDraftResponse;
import com.buildassist.dto.DraftDtos.SendDraftResponse;
import com.buildassist.dto.DraftDtos.UpdateDraftRequest;
import org.springframework.stereotype.Service;

@Service
public class DraftService {

    public GenerateDraftResponse generateDraft(Long userId, Long emailId) {
        throw new UnsupportedOperationException("Generate draft not implemented yet");
    }

    public void updateDraft(Long userId, Long emailId, Long draftId, UpdateDraftRequest request) {
        throw new UnsupportedOperationException("Update draft not implemented yet");
    }

    public SendDraftResponse sendDraft(Long userId, Long emailId, Long draftId, String draftBody) {
        throw new UnsupportedOperationException("Send draft not implemented yet");
    }
}
