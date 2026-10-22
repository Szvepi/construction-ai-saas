package com.buildassist.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class QuotePdfService {

    private static final List<Path> FONT_PATHS = List.of(
        Path.of("/usr/share/fonts/ttf-dejavu/DejaVuSans.ttf"),
        Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path.of("C:/Windows/Fonts/arial.ttf"),
        Path.of("C:/Windows/Fonts/ARIAL.TTF")
    );

    public byte[] render(String html) {
        Path fontPath = FONT_PATHS.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("A PDF-generáláshoz magyar karaktereket támogató betűkészlet szükséges"));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(fontPath.toFile(), "DejaVu Sans");
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Az árajánlat PDF-generálása sikertelen", ex);
        }
    }
}
