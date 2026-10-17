package com.example.mergab.Controller;

import com.example.mergab.Api.ApiException;
import com.example.mergab.Api.ApiResponse;
import com.example.mergab.Model.User;
import com.example.mergab.Service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/whatsapp")
@RequiredArgsConstructor
public class WhatsAppController {

    private final WhatsAppService whatsAppService;

    // المنظم يجرب إن الواتساب شغال: يرسل رسالة لرقمه
    @PostMapping("/test")
    public ResponseEntity<?> sendTest(@AuthenticationPrincipal User user) {
        if (user.getOrganizer() == null) {
            throw new ApiException("Organizer phone number not found");
        }

        whatsAppService.sendMessage(user.getOrganizer().getPhoneNumber(),
                "✅ رسالة تجريبية من مِرقاب، الواتساب شغال!\nMirqab WhatsApp test message.");

        return ResponseEntity.status(200).body(new ApiResponse("Test message sent"));
    }
}
