package com.example.mergab.Controller;

import com.example.mergab.Api.ApiResponse;
import com.example.mergab.DTO.OrganizerDTO;
import com.example.mergab.Model.User;
import com.example.mergab.Service.NotificationService;
import com.example.mergab.Service.OrganizerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizer")
@RequiredArgsConstructor
public class OrganizerController {

    private final OrganizerService organizerService;
    private final NotificationService notificationService;

    @GetMapping("/get-all")
    public ResponseEntity<?> getAllOrganizers() {
        return ResponseEntity.status(200).body(organizerService.getAllOrganizers());
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody @Valid OrganizerDTO dto) {
        organizerService.register(dto);
        notificationService.organizerWelcome(dto.getPhoneNumber(), dto.getFullName());
        return ResponseEntity.status(201).body(new ApiResponse("Organizer registered successfully"));
    }



    @DeleteMapping("/delete/{id}")
    public ResponseEntity<?> deleteOrganizer(@PathVariable Integer id) {
        organizerService.deleteOrganizer(id);
        return ResponseEntity.status(200).body(new ApiResponse("Organizer deleted successfully"));
    }

    @PutMapping("/update-my-phone")
    public ResponseEntity<?> updateMyPhone(@AuthenticationPrincipal User user, @RequestParam String phoneNumber) {

        organizerService.updateMyPhone(user.getId(), phoneNumber);

        return ResponseEntity.status(200).body(new ApiResponse("Phone number updated successfully"));
    }
}
