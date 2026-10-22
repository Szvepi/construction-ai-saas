package com.buildassist.service;

import com.buildassist.model.CompanyProfile;
import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class QuoteEmailMessageFactory {

    public MimeMessage create(
            String fromAddress,
            String toAddress,
            String subject,
            byte[] pdf,
            String filename,
            String clientName,
            CompanyProfile profile) throws MessagingException {
        MimeMessage message = new MimeMessage(Session.getDefaultInstance(new Properties(), null));
        message.setFrom(new InternetAddress(fromAddress));
        message.addRecipient(MimeMessage.RecipientType.TO, new InternetAddress(toAddress));
        message.setSubject(subject, "UTF-8");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(buildBody(clientName, profile), "UTF-8");

        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setDataHandler(new DataHandler(new ByteArrayDataSource(pdf, "application/pdf")));
        attachmentPart.setFileName(filename);
        attachmentPart.setDisposition(MimeBodyPart.ATTACHMENT);

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachmentPart);
        message.setContent(multipart);
        message.saveChanges();
        return message;
    }

    private String buildBody(String clientName, CompanyProfile profile) {
        String salutation = clientName == null || clientName.isBlank() ? "Érdeklődő" : clientName;
        StringBuilder body = new StringBuilder("Tisztelt ").append(salutation).append("!\n\n")
            .append("Csatoltan küldjük az Ön részére készített árajánlatot.\n\n")
            .append("Amennyiben kérdése merülne fel, készséggel állunk rendelkezésére.\n\n")
            .append("Üdvözlettel,\n");
        if (profile != null && profile.getOfferorName() != null && !profile.getOfferorName().isBlank()) {
            body.append(profile.getOfferorName()).append('\n');
        }
        if (profile != null && profile.getOfferorPhone() != null && !profile.getOfferorPhone().isBlank()) {
            body.append("Telefon: ").append(profile.getOfferorPhone()).append('\n');
        }
        return body.toString();
    }
}
