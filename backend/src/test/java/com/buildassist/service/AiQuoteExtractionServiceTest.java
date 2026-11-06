package com.buildassist.service;

import com.buildassist.dto.AiQuoteDtos;
import com.buildassist.model.Email;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuoteExtractionServiceTest {

    @Test
    void sendsJsonAndEmailBracesAsLiteralPromptContent() {
        ChatModel chatModel = mock(ChatModel.class);
        String responseJson = """
                {"client_name":"Acme","work_items":[],"unmapped_requests":[],"review_warnings":[]}
                """;
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(responseJson)))));

        AiQuoteExtractionService service =
                new AiQuoteExtractionService(chatModel, new ObjectMapper().findAndRegisterModules());
        Email email = new Email();
        email.setId(9L);
        email.setBodyText("Please quote {the requested work}; ignore {templateVariable}.");
        String notes = "Keep {these braces} unchanged.";

        AiQuoteDtos.StructuredExtraction result = service.extract(email, List.of(), notes);

        assertEquals("Acme", result.clientName());
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Message message = promptCaptor.getValue().getInstructions().getFirst();
        assertTrue(message.getText().contains("\"client_name\": \"string or null\""));
        assertTrue(message.getText().contains(email.getBodyText()));
        assertTrue(message.getText().contains(notes));
    }
}
