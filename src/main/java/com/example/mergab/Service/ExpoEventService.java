package com.example.mergab.Service;

import com.example.mergab.Api.ApiException;
import com.example.mergab.Model.ExpoEvent;
import com.example.mergab.Model.Organizer;
import com.example.mergab.Model.Zone;
import com.example.mergab.Repository.ExpoEventRepository;
import com.example.mergab.Repository.OrganizerRepository;
import com.example.mergab.Repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.mergab.Model.ZoneConnection;
import com.example.mergab.Repository.ZoneConnectionRepository;
import java.util.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExpoEventService {

    private final ExpoEventRepository expoEventRepository;
    private final ZoneRepository zoneRepository;
    private final OrganizerRepository organizerRepository;
    private final ZoneConnectionRepository zoneConnectionRepository;
    private final NotificationService notificationService;

    // كل الفعاليات (للأدمن والزوار)
    public List<ExpoEvent> getExpoEvents() {
        return expoEventRepository.findAll();
    }

    // الفعاليات اللي أضافها منظم معيّن
    public List<ExpoEvent> getEventsByOrganizer(Integer organizerId) {
        getOrganizer(organizerId);
        return expoEventRepository.findExpoEventsByOrganizer_Id(organizerId);
    }

    public void addExpoEvent(Integer organizerId, Integer zoneId, ExpoEvent expoEvent) {
        Organizer organizer = getOrganizer(organizerId);

        // Check if zone exists
        Zone zone = zoneRepository.findZoneById(zoneId);
        if (zone == null) {
            throw new ApiException("Zone not found");
        }

        // Validate event dates
        if (!expoEvent.getEndDateTime().isAfter(expoEvent.getStartDateTime())) {
            throw new ApiException("End date must be after start date");
        }

        expoEvent.setZone(zone);
        expoEvent.setOrganizer(organizer);
        expoEventRepository.save(expoEvent);
    }

    //update expo event
    public void updateExpoEvent(Integer id, ExpoEvent expoEvent) {
        ExpoEvent oldEvent = getEventById(id);

        // Validate event dates
        if (!expoEvent.getEndDateTime().isAfter(expoEvent.getStartDateTime())) {
            throw new ApiException("End date must be after start date");
        }

        oldEvent.setName(expoEvent.getName());
        oldEvent.setStartDateTime(expoEvent.getStartDateTime());
        oldEvent.setEndDateTime(expoEvent.getEndDateTime());
        oldEvent.setExpectedDemand(expoEvent.getExpectedDemand());
        oldEvent.setMapX(expoEvent.getMapX());
        oldEvent.setMapY(expoEvent.getMapY());

        expoEventRepository.save(oldEvent);

        // WhatsApp: tell visitors inside the expo about the new time
        notificationService.eventUpdated(oldEvent);
    }

    //delete expo event
    public void deleteExpoEvent(Integer id) {
        ExpoEvent expoEvent = getEventById(id);
        String eventName = expoEvent.getName();
        String zoneName = expoEvent.getZone().getName();

        expoEventRepository.delete(expoEvent);

        // WhatsApp: tell visitors inside the expo the event is cancelled
        notificationService.eventCancelled(eventName, zoneName);
    }

    //change event zone
    public void changeEventZone(Integer eventId, Integer zoneId) {
        ExpoEvent expoEvent = getEventById(eventId);

        // Check if zone exists
        Zone zone = zoneRepository.findZoneById(zoneId);
        if (zone == null) {
            throw new ApiException("Zone not found");
        }

        // Prevent assigning the same zone
        if (expoEvent.getZone().getId().equals(zoneId)) {
            throw new ApiException("Event is already assigned to this zone");
        }

        String oldZoneName = expoEvent.getZone().getName();

        expoEvent.setZone(zone);
        expoEventRepository.save(expoEvent);

        // WhatsApp: tell visitors inside the expo about the new location
        notificationService.eventZoneChanged(expoEvent, oldZoneName);
    }

    // Get events by zone
    public List<ExpoEvent> getEventsByZone(Integer zoneId) {
        Zone zone = zoneRepository.findZoneById(zoneId);
        if (zone == null) {
            throw new ApiException("Zone not found");
        }
        return expoEventRepository.findByZone_Id(zoneId);
    }

    // Get event by ID
    public ExpoEvent getEventById(Integer id) {
        ExpoEvent expoEvent = expoEventRepository.findExpoEventById(id);
        if (expoEvent == null) {
            throw new ApiException("Event not found");
        }
        return expoEvent;
    }

    // Get upcoming and ongoing events
    public List<ExpoEvent> getUpcomingEvents() {
        return expoEventRepository
                .findByEndDateTimeAfterOrderByStartDateTimeAsc(LocalDateTime.now());
    }


    // Get today's events
    public List<ExpoEvent> getTodayEvents() {

        LocalDate today = LocalDate.now();

        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();

        return expoEventRepository.findByStartDateTimeBeforeAndEndDateTimeAfterOrderByStartDateTimeAsc(dayEnd, dayStart);
    }

    // Find a route from the visitor's current zone to the event's zone using BFS
    public List<String> getRouteToEvent(Integer eventId, Integer fromZoneId) {
        ExpoEvent event = getEventById(eventId);

        Zone startZone = zoneRepository.findZoneById(fromZoneId);
        if (startZone == null) {
            throw new ApiException("Starting zone not found");
        }

        Zone destination = event.getZone();
        if (destination == null) {
            throw new ApiException("Event has no assigned zone");
        }

        Queue<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> previous = new HashMap<>();
        Set<Integer> visited = new HashSet<>();

        queue.add(fromZoneId);
        visited.add(fromZoneId);

        while (!queue.isEmpty()) {
            Integer current = queue.poll();

            if (current.equals(destination.getId())) {
                break;
            }

            for (ZoneConnection connection : zoneConnectionRepository.findByFromZone_Id(current)) {
                Integer next = connection.getToZone().getId();

                if (visited.add(next)) {
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }

        if (!visited.contains(destination.getId())) {
            throw new ApiException("No route found to this event");
        }

        List<Integer> routeIds = new ArrayList<>();
        Integer current = destination.getId();

        while (current != null) {
            routeIds.add(current);
            current = previous.get(current);
        }

        Collections.reverse(routeIds);

        List<String> route = new ArrayList<>();
        for (Integer zoneId : routeIds) {
            Zone zone = zoneRepository.findZoneById(zoneId);
            route.add(zone.getName());
        }

        return route;
    }
    // ===== helpers =====

    private Organizer getOrganizer(Integer organizerId) {
        Organizer organizer = organizerRepository.findOrganizerById(organizerId);
        if (organizer == null) {
            throw new ApiException("Organizer not found");
        }
        return organizer;
    }
}
