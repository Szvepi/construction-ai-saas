package com.buildassist.service;

import com.buildassist.dto.EmailDtos.EmailDetailResponse;
import com.buildassist.dto.EmailDtos.EmailSummaryResponse;
import com.buildassist.dto.EmailDtos.RefreshEmailsResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    public List<EmailSummaryResponse> listEmails(Long userId) {
        throw new UnsupportedOperationException("List emails not implemented yet");
    }

    public EmailDetailResponse getEmail(Long userId, Long emailId) {
        throw new UnsupportedOperationException("Get email not implemented yet");
    }

    public RefreshEmailsResponse refreshFromGmail(Long userId) {
        throw new UnsupportedOperationException("Refresh emails not implemented yet");
    }
}
