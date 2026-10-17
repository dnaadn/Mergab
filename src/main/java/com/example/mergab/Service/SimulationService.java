package com.example.mergab.Service;

import com.example.mergab.Api.ApiException;
import com.example.mergab.Model.HistoricalAttendance;
import com.example.mergab.Model.Organizer;
import com.example.mergab.Model.Simulation;
import com.example.mergab.Model.SimulationSnapshot;
import com.example.mergab.Model.Zone;
import com.example.mergab.Repository.SimulationSnapshotRepository;
import com.example.mergab.Repository.ZoneConnectionRepository;
import com.example.mergab.Repository.ZoneRepository;
import com.example.mergab.Simulation.*;
import com.example.mergab.Repository.HistoricalAttendanceRepository;
import com.example.mergab.Repository.OrganizerRepository;
import com.example.mergab.Repository.SimulationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.mergab.Model.ExpoEvent;
import com.example.mergab.Repository.ExpoEventRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulationService {

    private final SimulationRepository simulationRepository;
    private final HistoricalAttendanceRepository historicalAttendanceRepository;
    private final OrganizerRepository organizerRepository;
    private final SimulationSnapshotRepository simulationSnapshotRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneConnectionRepository zoneConnectionRepository;
    private final ExpoEventRepository expoEventRepository;
    private final NotificationService notificationService;
    private final PrayerTimesService prayerTimesService = new PrayerTimesService();

    private final CrowdSimulationEngine crowdSimulationEngine = new CrowdSimulationEngine();
    private final EventDrivenSimulationRunner eventDrivenSimulationRunner =
            new EventDrivenSimulationRunner(
                    crowdSimulationEngine,
                    new EventDrivenSimulationEngine(),
                    prayerTimesService
            );

    /**
     * Selected Osaka day versus the average of all stored days for that expo.
     * E.g. 10% higher daily attendance -> 10% higher event arrivals.
     * Cap the change at +/-25% to avoid overwhelming a small test venue.
     * With only one stored day, leave demand unchanged (no baseline available).
     */
    private List<ExpoEvent> applyHistoricalAttendance(Simulation simulation, List<ExpoEvent> events) {
        HistoricalAttendance selected = simulation.getHistoricalAttendance();
        if (selected == null || selected.getExpoName() == null || events.isEmpty()) {
            return events;
        }
        List<HistoricalAttendance> sameExpo =
                historicalAttendanceRepository.findByExpoNameOrderByAttendanceDateDesc(selected.getExpoName());
        double factor = HistoricalAttendanceDemandScaler.multiplier(selected, sameExpo);
        return HistoricalAttendanceDemandScaler.scaledEvents(events, factor);
    }

    // كل المحاكاة (للأدمن)
    public List<Simulation> getAllSimulations() {
        return simulationRepository.findAll();
    }

    // المحاكاة اللي أنشأها منظم معيّن
    public List<Simulation> getSimulationsByOrganizer(Integer organizerId) {
        getOrganizer(organizerId);
        return simulationRepository.findSimulationsByOrganizer_Id(organizerId);
    }

    public void addSimulation(Integer organizerId, Integer historicalAttendanceId, Simulation simulation) {
        Organizer organizer = getOrganizer(organizerId);

        HistoricalAttendance attendance = historicalAttendanceRepository.findHistoricalAttendanceById(historicalAttendanceId);
        if (attendance == null) {
            throw new ApiException("Historical attendance not found");
        }

        simulation.setHistoricalAttendance(attendance);
        simulation.setOrganizer(organizer);
        // هذي يحددها النظام، مو المستخدم
        simulation.setStatus("PENDING");
        simulation.setStartDateTime(null);
        simulation.setEndDateTime(null);

        simulationRepository.save(simulation);
    }

    // التعديل مسموح بس قبل التشغيل
    public void updateSimulation(Integer id, Simulation newSimulation) {
        Simulation oldSimulation = getSimulation(id);
        if (!oldSimulation.getStatus().equals("PENDING")) {
            throw new ApiException("Only pending simulations can be updated");
        }

        oldSimulation.setDurationMinutes(newSimulation.getDurationMinutes());
        oldSimulation.setRandomSeed(newSimulation.getRandomSeed());

        simulationRepository.save(oldSimulation);
    }

    /** Run a pending simulation and persist one snapshot per zone per minute. */
    @Transactional
    public List<SimulationSnapshot> runSimulation(Integer simulationId) {
        Simulation simulation = getSimulation(simulationId);
        if (!"PENDING".equals(simulation.getStatus())) {
            throw new ApiException("Only pending simulations can be run");
        }

        List<Zone> zones = zoneRepository.findAll();
        if (zones.isEmpty()) {
            throw new ApiException("Cannot run a simulation without zones");
        }

        // Osaka's daily attendance changes the relative event demand, but raw
        // Osaka totals never become visitors in this smaller venue model.
        if (simulation.getDurationMinutes() == null || simulation.getDurationMinutes() < 1
                || simulation.getDurationMinutes() > 600) {
            throw new ApiException("Simulation duration must be between 1 and 600 minutes");
        }
        if (simulation.getRandomSeed() == null) {
            throw new ApiException("Simulation random seed is required");
        }
        for (Zone zone : zones) {
            if (zone.getCapacity() == null || zone.getInitialVisitors() == null
                    || zone.getCapacity() < 0 || zone.getInitialVisitors() < 0
                    || zone.getInitialVisitors() > zone.getCapacity()) {
                throw new ApiException("Invalid initial visitors or capacity for zone: " + zone.getName());
            }
        }

        simulation.setStatus("RUNNING");
        simulation.setStartDateTime(LocalDateTime.now());
        simulationRepository.save(simulation);

        LocalDateTime simulationStart = simulation.getStartDateTime();
        LocalDateTime simulationEnd = simulationStart.plusMinutes(
                simulation.getDurationMinutes()
        );

        List<ExpoEvent> events = expoEventRepository
                .findExpoEventsByOrganizer_Id(
                        simulation.getOrganizer().getId()
                )
                .stream()
                // Only include events whose actual event interval overlaps
                // this simulation. Do not simulate pre-arrivals for an event
                // that begins after the simulation has already ended.
                .filter(event ->
                        event.getStartDateTime().isBefore(simulationEnd)
                                && event.getEndDateTime().isAfter(simulationStart)
                )
                .toList();

        List<SimulationSnapshotRecord> records =
                eventDrivenSimulationRunner.runSimulation(
                        zones,
                        zoneConnectionRepository.findAll(),
                        applyHistoricalAttendance(simulation, events),
                        simulationStart,
                        simulation.getDurationMinutes(),
                        30,
                        simulation.getRandomSeed()
                );

        Map<Integer, Zone> zonesById = zones.stream().collect(
                Collectors.toMap(Zone::getId, Function.identity())
        );
        List<SimulationSnapshot> snapshots = records.stream().map(record -> {
            Zone zone = zonesById.get(record.zoneId());
            if (zone == null) {
                throw new ApiException("Simulation produced a snapshot for an unknown zone");
            }
            SimulationSnapshot snapshot = new SimulationSnapshot();
            snapshot.setSimulation(simulation);
            snapshot.setZone(zone);
            snapshot.setMinute(record.minute());
            snapshot.setOccupancy(record.occupancy());
            snapshot.setQueueLength(record.queueLength());
            return snapshot;
        }).toList();

        simulationSnapshotRepository.saveAll(snapshots);
        simulation.setStatus("COMPLETED");
        simulation.setEndDateTime(simulationStart.plusMinutes(simulation.getDurationMinutes()));
        simulationRepository.save(simulation);

        // WhatsApp: send the organizer a summary of the crowded zones
        notificationService.simulationCompleted(simulation, zones, records);
        return snapshots;
    }


    /**
     * Continue a completed simulation by deterministically replaying the full
     * timeline with the original seed and appending only the new minutes.
     * This reconstructs active event/prayer route cohorts without adding a new
     * database column or storing transient engine internals in snapshot fields.
     */
    @Transactional
    public List<SimulationSnapshot> continueSimulation(
            Integer simulationId,
            Integer additionalDurationMinutes) {

        Simulation simulation = getSimulation(simulationId);
        if (!"COMPLETED".equals(simulation.getStatus())) {
            throw new ApiException("Only completed simulations can be continued");
        }
        if (additionalDurationMinutes == null || additionalDurationMinutes < 1) {
            throw new ApiException("Additional duration must be at least 1 minute");
        }

        List<SimulationSnapshot> existingSnapshots =
                simulationSnapshotRepository.findBySimulation_Id(simulationId);
        if (existingSnapshots == null || existingSnapshots.isEmpty()) {
            throw new ApiException("Cannot continue a simulation without saved snapshots");
        }
        if (simulation.getStartDateTime() == null || simulation.getRandomSeed() == null) {
            throw new ApiException("Simulation start time and random seed are required to continue");
        }

        int lastMinute = existingSnapshots.stream()
                .mapToInt(SimulationSnapshot::getMinute).max().orElseThrow();
        int newTotalDuration = lastMinute + additionalDurationMinutes;
        if (newTotalDuration > 600) {
            throw new ApiException("Total simulation duration cannot exceed 600 minutes");
        }

        List<Zone> zones = zoneRepository.findAll();
        if (zones.isEmpty()) throw new ApiException("Cannot continue a simulation without zones");
        Map<Integer, Zone> zonesById = zones.stream().collect(
                Collectors.toMap(Zone::getId, Function.identity()));

        // Verify the saved final minute has exactly one snapshot per current zone.
        Map<Integer, SimulationSnapshot> lastByZone = new java.util.HashMap<>();
        for (SimulationSnapshot snapshot : existingSnapshots) {
            if (snapshot.getMinute() == lastMinute) {
                lastByZone.put(snapshot.getZone().getId(), snapshot);
            }
        }
        if (lastByZone.size() != zones.size() || !lastByZone.keySet().equals(zonesById.keySet())) {
            throw new ApiException("The latest saved minute must contain one snapshot for every current zone");
        }

        LocalDateTime originalStart = simulation.getStartDateTime();
        LocalDateTime replayEnd = originalStart.plusMinutes(newTotalDuration);
        List<ExpoEvent> events = expoEventRepository
                .findExpoEventsByOrganizer_Id(simulation.getOrganizer().getId())
                .stream()
                // Match the original run's event-window rule during replay.
                .filter(event -> event.getStartDateTime().isBefore(replayEnd)
                        && event.getEndDateTime().isAfter(originalStart))
                .toList();

        List<com.example.mergab.Model.ZoneConnection> connections = zoneConnectionRepository.findAll();
        boolean hasPrayerDestination = zones.stream().anyMatch(zone ->
                Boolean.TRUE.equals(zone.getHasPrayerArea())
                        && zone.getFacilities() != null
                        && zone.getFacilities().stream().anyMatch(f -> f != null
                                && "PRAYER_ROOM".equalsIgnoreCase(f.getType())));

        List<SimulationSnapshotRecord> replay;
        int replayMinuteOffset;
        int firstNewRecordMinute;
        if (!events.isEmpty() || hasPrayerDestination) {
            replayMinuteOffset = 0;
            firstNewRecordMinute = lastMinute;
            // Aggregate snapshots cannot store active event/prayer route cohorts.
            // Replay the full deterministic timeline to reconstruct those journeys.
            replay = eventDrivenSimulationRunner.runSimulation(
                    zones, connections, applyHistoricalAttendance(simulation, events), originalStart,
                    newTotalDuration, 30, simulation.getRandomSeed());
        } else {
            replayMinuteOffset = lastMinute;
            firstNewRecordMinute = 0;
            // With no event or prayer journeys to restore, resume directly from
            // the latest persisted aggregate state instead of discarding it.
            Map<Integer, Integer> occupancy = new java.util.HashMap<>();
            Map<Integer, Integer> queues = new java.util.HashMap<>();
            lastByZone.forEach((zoneId, snapshot) -> {
                occupancy.put(zoneId, snapshot.getOccupancy());
                queues.put(zoneId, snapshot.getQueueLength());
            });
            replay = eventDrivenSimulationRunner.runSimulation(
                    zones, connections, List.of(), originalStart.plusMinutes(lastMinute),
                    additionalDurationMinutes, 30,
                    new SimulationState(occupancy, queues), simulation.getRandomSeed());
        }

        Map<String, SimulationSnapshot> existingByMinuteAndZone = new java.util.HashMap<>();
        for (SimulationSnapshot snapshot : existingSnapshots) {
            existingByMinuteAndZone.put(snapshot.getMinute() + ":" + snapshot.getZone().getId(), snapshot);
        }
        List<SimulationSnapshot> newSnapshots = replay.stream()
                .filter(record -> record.minute() > firstNewRecordMinute)
                .map(record -> {
                    Zone zone = zonesById.get(record.zoneId());
                    if (zone == null) throw new ApiException("Simulation produced a snapshot for an unknown zone");
                    int absoluteMinute = record.minute() + replayMinuteOffset;
                    String key = absoluteMinute + ":" + record.zoneId();
                    if (existingByMinuteAndZone.containsKey(key)) {
                        throw new ApiException("A snapshot already exists for minute " + record.minute()
                                + " and zone " + record.zoneId());
                    }
                    SimulationSnapshot snapshot = new SimulationSnapshot();
                    snapshot.setSimulation(simulation);
                    snapshot.setZone(zone);
                    snapshot.setMinute(absoluteMinute);
                    snapshot.setOccupancy(record.occupancy());
                    snapshot.setQueueLength(record.queueLength());
                    return snapshot;
                }).toList();

        simulation.setStatus("RUNNING");
        simulationRepository.save(simulation);
        simulationSnapshotRepository.saveAll(newSnapshots);
        simulation.setDurationMinutes(newTotalDuration);
        simulation.setEndDateTime(originalStart.plusMinutes(newTotalDuration));
        simulation.setStatus("COMPLETED");
        simulationRepository.save(simulation);
        return newSnapshots;
    }



    public void deleteSimulation(Integer id) {
        Simulation simulation = getSimulation(id);
        if (simulation.getStatus().equals("RUNNING")) {
            throw new ApiException("Cannot delete a running simulation");
        }
        simulationRepository.delete(simulation);
    }

    // Get simulation by ID
    public Simulation getSimulationById(Integer id) {
        return getSimulation(id);
    }

    // Get simulations by status
    public List<Simulation> getSimulationsByStatus(String status) {

        // Validate simulation status
        if (!List.of("PENDING", "RUNNING", "COMPLETED", "FAILED").contains(status)) {
            throw new ApiException("Invalid simulation status");
        }

        return simulationRepository.findByStatus(status);
    }

    // ===== helpers =====

    private Organizer getOrganizer(Integer organizerId) {
        Organizer organizer = organizerRepository.findOrganizerById(organizerId);
        if (organizer == null) {
            throw new ApiException("Organizer not found");
        }
        return organizer;
    }

    private Simulation getSimulation(Integer simulationId) {
        Simulation simulation = simulationRepository.findSimulationById(simulationId);
        if (simulation == null) {
            throw new ApiException("Simulation not found");
        }
        return simulation;
    }
}
