package com.example.mergab.Controller;

import com.example.mergab.Api.ApiResponse;
import com.example.mergab.DTO.UpdateVisitorDTO;
import com.example.mergab.DTO.VisitorDTO;
import com.example.mergab.Model.User;
import com.example.mergab.Service.EmailService;
import com.example.mergab.Service.NotificationService;
import com.example.mergab.Service.VisitorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visitor")
@RequiredArgsConstructor
public class VisitorController {

    private final VisitorService visitorService;
    private final EmailService emailService;
    private final NotificationService notificationService;

    @GetMapping("/get-all")
    public ResponseEntity<?> getAllVisitors() {
        return ResponseEntity.status(200).body(visitorService.getAllVisitors());
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody @Valid VisitorDTO dto) {
        String qrToken = visitorService.register(dto);

        // Sent after the visitor is saved successfully (runs in background)
        emailService.sendWelcomeEmail(dto.getEmail(), dto.getFullName(), qrToken);
        notificationService.visitorWelcome(dto.getPhoneNumber(), dto.getFullName());

        return ResponseEntity.status(201).body(new ApiResponse("Visitor registered successfully"));
    }

    @PutMapping("/update-my-info")
    public ResponseEntity<?> updateVisitor(@AuthenticationPrincipal User user, @RequestBody @Valid UpdateVisitorDTO dto) {
        visitorService.updateVisitor(user.getId(), dto);
        return ResponseEntity.status(200).body(new ApiResponse("Visitor updated successfully"));
    }





    // Get the visitor's QR code as a PNG image
    @GetMapping(value = "/my-qr-code", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getVisitorQrCode(@AuthenticationPrincipal User user) {

        return ResponseEntity.status(200).contentType(MediaType.IMAGE_PNG).body(visitorService.getVisitorQrCode(user.getId()));
    }


}
