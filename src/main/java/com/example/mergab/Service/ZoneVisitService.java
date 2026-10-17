package com.example.mergab.Service;


import com.example.mergab.Api.ApiException;
import com.example.mergab.DTO.ZoneVisitResponseDTO;
import com.example.mergab.Model.Visitor;
import com.example.mergab.Model.Zone;
import com.example.mergab.Model.ZoneVisit;
import com.example.mergab.Repository.VisitorRepository;
import com.example.mergab.Repository.ZoneRepository;
import com.example.mergab.Repository.ZoneVisitRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ZoneVisitService {

    private final ZoneVisitRepository zoneVisitRepository;
    private final VisitorRepository visitorRepository;
    private final ZoneRepository zoneRepository;
    private final NotificationService notificationService;

    // Register visitor entry after scanning their QR code
    @Transactional
    public void checkIn(String qrToken, Integer zoneId) {

        Visitor visitor = visitorRepository.findVisitorForUpdate(qrToken);
        if (visitor == null) {
            throw new ApiException("Invalid visitor QR code");
        }

        Zone zone = zoneRepository.findZoneForUpdate(zoneId);
        if (zone == null) {
            throw new ApiException("Zone not found");
        }

        // Prevent a visitor from entering multiple zones at the same time
        ZoneVisit activeVisit = zoneVisitRepository.findByVisitor_IdAndCheckOutTimeIsNull(visitor.getId());

        if (activeVisit != null) {
            throw new ApiException("Visitor must check out from the current zone first");
        }

        // Prevent entry when the zone reaches its maximum capacity
        long currentOccupancy = zoneVisitRepository.countByZone_IdAndCheckOutTimeIsNull(zoneId);

        if (currentOccupancy >= zone.getCapacity()) {
            // WhatsApp: tell the visitor and suggest a less crowded zone
            notificationService.zoneFullForVisitor(visitor, zone);
            throw new ApiException("Zone is at full capacity");
        }

        ZoneVisit visit = new ZoneVisit(null, LocalDateTime.now(), null, false, visitor, zone);
        zoneVisitRepository.save(visit);

        // WhatsApp: confirm entry to the visitor + alert organizers at 80% / full
        notificationService.visitorCheckedIn(visitor, zone, currentOccupancy + 1);
        notificationService.checkZoneThresholds(zone, currentOccupancy, currentOccupancy + 1);
    }

    // Register visitor exit from the specified zone
    @Transactional
    public void checkOut(String qrToken, Integer zoneId) {

        Visitor visitor = visitorRepository.findVisitorForUpdate(qrToken);
        if (visitor == null) {
            throw new ApiException("Invalid visitor QR code");
        }

        ZoneVisit activeVisit =
                zoneVisitRepository.findByVisitor_IdAndCheckOutTimeIsNull(visitor.getId());

        if (activeVisit == null) {
            throw new ApiException("Visitor has no active zone visit");
        }

        // Visitor must check out from the same zone they entered
        if (!activeVisit.getZone().getId().equals(zoneId)) {
            throw new ApiException("Visitor is not inside this zone");
        }

        activeVisit.setCheckOutTime(LocalDateTime.now());
        zoneVisitRepository.save(activeVisit);
    }

    // Count visitors currently inside a zone
    public long getCurrentOccupancy(Integer zoneId) {

        Zone zone = zoneRepository.findZoneById(zoneId);
        if (zone == null) {
            throw new ApiException("Zone not found");
        }

        return zoneVisitRepository.countByZone_IdAndCheckOutTimeIsNull(zoneId);
    }

    // Get all visits for a specific visitor
    @Transactional
    public List<ZoneVisitResponseDTO> getVisitorHistory(Integer visitorId) {

        Visitor visitor = visitorRepository.findVisitorById(visitorId);
        if (visitor == null) {
            throw new ApiException("Visitor not found");
        }

        return zoneVisitRepository.findByVisitor_Id(visitorId).stream().map(this::toResponseDTO).toList();
    }

    // Get all visitors currently inside a zone
    @Transactional
    public List<ZoneVisitResponseDTO> getActiveVisits(Integer zoneId) {

        Zone zone = zoneRepository.findZoneById(zoneId);
        if (zone == null) {
            throw new ApiException("Zone not found");
        }

        return zoneVisitRepository.findByZone_IdAndCheckOutTimeIsNull(zoneId).stream().map(this::toResponseDTO).toList();
    }


    // Manually close an active visit when the visitor forgot to check out
    @Transactional
    public void manualCheckOut(String qrToken) {

        Visitor visitor = visitorRepository.findVisitorForUpdate(qrToken);
        if (visitor == null) {
            throw new ApiException("Invalid visitor QR code");
        }

        ZoneVisit activeVisit =
                zoneVisitRepository.findByVisitor_IdAndCheckOutTimeIsNull(visitor.getId());

        if (activeVisit == null) {
            throw new ApiException("Visitor has no active zone visit");
        }

        activeVisit.setCheckOutTime(LocalDateTime.now());
        activeVisit.setManualCheckOut(true);
        zoneVisitRepository.save(activeVisit);
    }

    // Get the visitor's active zone details before manual check-out
    @Transactional
    public ZoneVisitResponseDTO getActiveVisitByQr(String qrToken) {

        Visitor visitor = visitorRepository.findVisitorByQrToken(qrToken);
        if (visitor == null) {
            throw new ApiException("Invalid visitor QR code");
        }

        ZoneVisit activeVisit =
                zoneVisitRepository.findByVisitor_IdAndCheckOutTimeIsNull(visitor.getId());

        if (activeVisit == null) {
            throw new ApiException("Visitor has no active zone visit");
        }

        return toResponseDTO(activeVisit);
    }


    // Convert a zone visit into a response containing visitor and zone details
    public ZoneVisitResponseDTO toResponseDTO(ZoneVisit visit) {

        return new ZoneVisitResponseDTO(
                visit.getId(),
                visit.getVisitor().getId(),
                visit.getZone().getId(),
                visit.getZone().getName(),
                visit.getCheckInTime(),
                visit.getCheckOutTime(),
                visit.getManualCheckOut());
    }
}