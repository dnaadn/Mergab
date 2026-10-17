package com.example.mergab.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

// إرسال رسائل واتساب عن طريق UltraMsg
// لو الإعدادات فاضية أو whatsapp.enabled=false: الرسالة تنطبع في الكونسول بس (مفيد للتجربة)
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    private final RestTemplate restTemplate;

    @Value("${whatsapp.enabled:false}")
    private boolean enabled;

    @Value("${whatsapp.api-url:https://api.ultramsg.com}")
    private String apiUrl;

    @Value("${whatsapp.instance-id:}")
    private String instanceId;

    @Value("${whatsapp.token:}")
    private String token;

    // يرسل في الخلفية، وأي خطأ ما يوقف العملية الأساسية
    @Async
    public void sendMessage(String phoneNumber, String message) {
        String to = toInternational(phoneNumber);

        if (to == null) {
            log.warn("[WhatsApp] invalid phone number: {}", phoneNumber);
            return;
        }

        if (!enabled || instanceId.isBlank() || token.isBlank()) {
            log.info("[WhatsApp - disabled] to {}:\n{}", to, message);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("token", token);
            body.add("to", to);
            body.add("body", message);

            String url = apiUrl + "/" + instanceId + "/messages/chat";
            String response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);

            // UltraMsg يرجع 200 حتى لو فيه خطأ، فنطبع الرد عشان نعرف وش صار
            if (response != null && response.contains("\"error\"")) {
                log.error("[WhatsApp] UltraMsg error for {}: {}", to, response);
            } else {
                log.info("[WhatsApp] sent to {} -> {}", to, response);
            }

        } catch (Exception e) {
            log.error("[WhatsApp] failed to send to {}", to, e);
        }
    }

    // 05XXXXXXXX  ->  +9665XXXXXXXX
    private String toInternational(String phone) {
        if (phone == null) return null;

        String digits = phone.replaceAll("[^0-9]", "");

        if (digits.matches("^05\\d{8}$")) return "+966" + digits.substring(1);
        if (digits.matches("^9665\\d{8}$")) return "+" + digits;

        return null;
    }
}
