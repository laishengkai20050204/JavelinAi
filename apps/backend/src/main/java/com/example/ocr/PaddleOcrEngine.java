package com.example.ocr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaddleOcrEngine implements OcrEngine {

    private final WebClient.Builder webClientBuilder;

    // 对应你 application.yaml 里的 ocr.paddle.base-url / timeout-ms
    @Value("${ocr.paddle.base-url:http://localhost:8081}")
    private String ocrBaseUrl;

    @Value("${ocr.paddle.timeout-ms:15000}")
    private long timeoutMs;

    @Override
    public List<OcrTextBox> recognize(BufferedImage image, String langHint) throws Exception {
        if (image == null) {
            throw new IllegalArgumentException("image is null");
        }

        // 1) BufferedImage -> PNG bytes
        byte[] pngBytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bos);
            pngBytes = bos.toByteArray();
        }

        log.debug("[PaddleOcrEngine] Calling OCR server at {}", ocrBaseUrl);

        WebClient client = webClientBuilder
                .clone()
                .baseUrl(ocrBaseUrl)
                .build();

        var resource = new ByteArrayResource(pngBytes) {
            @Override
            public String getFilename() {
                return "image.png";
            }
        };

        // 2) multipart/form-data 上传图片
        Map resp = client.post()
                .uri("/ocr")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file", resource))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

        if (resp == null) {
            log.warn("[PaddleOcrEngine] OCR server returned null response");
            return List.of();
        }

        Object boxesObj = resp.get("boxes");
        if (!(boxesObj instanceof List<?> list)) {
            log.warn("[PaddleOcrEngine] OCR response has no 'boxes' array: {}", resp);
            return List.of();
        }

        List<OcrTextBox> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;

            Object textObj = m.get("text");
            String text = textObj == null ? "" : String.valueOf(textObj);

            int x = toInt(m.get("x"));
            int y = toInt(m.get("y"));
            int w = toInt(m.get("width"));
            int h = toInt(m.get("height"));
            float conf = toFloat(m.get("confidence"));

            result.add(new OcrTextBox(text, x, y, w, h, conf));
        }


        log.info("[PaddleOcrEngine] OCR got {} boxes", result.size());
        return result;
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private float toFloat(Object o) {
        if (o instanceof Number n) return n.floatValue();
        try {
            return Float.parseFloat(String.valueOf(o));
        } catch (Exception e) {
            return 0f;
        }
    }
}
