
package com.example.mergab.Service;

import com.example.mergab.Api.ApiException;
import com.example.mergab.Model.*;
import com.example.mergab.Repository.ExpoEventRepository;
import com.example.mergab.Repository.HistoricalAttendanceRepository;
import com.example.mergab.Repository.OrganizerRepository;
import com.example.mergab.Repository.SimulationRepository;
import com.example.mergab.Repository.SimulationSnapshotRepository;
import com.example.mergab.Repository.ZoneConnectionRepository;
import com.example.mergab.Repository.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnableWebSecurity
@ExtendWith(SpringExtension.class)
class SimulationServiceTest {

    private SimulationRepository simulationRepository;
    private HistoricalAttendanceRepository historicalAttendanceRepository;
    private OrganizerRepository organizerRepository;
    private SimulationSnapshotRepository simulationSnapshotRepository;
    private ZoneRepository zoneRepository;
    private ZoneConnectionRepository zoneConnectionRepository;
    private ExpoEventRepository expoEventRepository;

    private SimulationService simulationService;

    @BeforeEach
    void setUp() {
        simulationRepository = mock(SimulationRepository.class);
        historicalAttendanceRepository =
                mock(HistoricalAttendanceRepository.class);
        organizerRepository = mock(OrganizerRepository.class);
        simulationSnapshotRepository =
                mock(SimulationSnapshotRepository.class);
        zoneRepository = mock(ZoneRepository.class);
        zoneConnectionRepository = mock(ZoneConnectionRepository.class);
        expoEventRepository = mock(ExpoEventRepository.class);

        simulationService = new SimulationService(
                simulationRepository,
                historicalAttendanceRepository,
                organizerRepository,
                simulationSnapshotRepository,
                zoneRepository,
                zoneConnectionRepository,
                expoEventRepository,
                mock(com.example.mergab.Service.NotificationService.class)
        );
    }

    @Test
    void shouldRunPendingSimulationAndSaveSnapshots() {
        // Arrange
        Organizer organizer = new Organizer();
        organizer.setId(1);

        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setOrganizer(organizer);
        simulation.setStatus("PENDING");
        simulation.setDurationMinutes(2);
        simulation.setRandomSeed(42L);

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Entrance");
        zone.setCapacity(100);
        zone.setInitialVisitors(10);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);
        when(zoneRepository.findAll()).thenReturn(List.of(zone));
        when(zoneConnectionRepository.findAll()).thenReturn(List.of());
        when(expoEventRepository.findExpoEventsByOrganizer_Id(1))
                .thenReturn(List.of());

        when(simulationSnapshotRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<SimulationSnapshot> snapshots =
                simulationService.runSimulation(1);

        // Assert
        assertNotNull(snapshots);
        assertEquals(3, snapshots.size()); // Minutes 0, 1, and 2
        assertEquals("COMPLETED", simulation.getStatus());
        assertNotNull(simulation.getStartDateTime());
        assertNotNull(simulation.getEndDateTime());

        assertTrue(
                simulation.getEndDateTime()
                        .isAfter(simulation.getStartDateTime())
                        || simulation.getEndDateTime()
                        .isEqual(simulation.getStartDateTime())
        );

        verify(simulationSnapshotRepository).saveAll(anyList());
        verify(simulationRepository, times(2)).save(simulation);
    }

    @Test
    void shouldRejectSimulationThatIsNotPending() {
        // Arrange
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("COMPLETED");

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);

        // Act and Assert
        assertThrows(
                ApiException.class,
                () -> simulationService.runSimulation(1)
        );

        verify(zoneRepository, never()).findAll();
        verify(simulationSnapshotRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldRejectSimulationWhenThereAreNoZones() {
        // Arrange
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("PENDING");

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);
        when(zoneRepository.findAll()).thenReturn(List.of());

        // Act and Assert
        assertThrows(
                ApiException.class,
                () -> simulationService.runSimulation(1)
        );

        assertEquals("PENDING", simulation.getStatus());
        verify(simulationSnapshotRepository, never()).saveAll(anyList());
    }


    @Test
    void shouldRejectSimulationWhenInitialVisitorsExceedCapacity() {
        // Arrange
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("PENDING");

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Entrance");
        zone.setCapacity(5);
        zone.setInitialVisitors(10);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);
        when(zoneRepository.findAll())
                .thenReturn(List.of(zone));

        // Act and Assert
        assertThrows(
                ApiException.class,
                () -> simulationService.runSimulation(1)
        );

        // The simulation must not start or save snapshots.
        assertEquals("PENDING", simulation.getStatus());
        assertNull(simulation.getStartDateTime());

        verify(simulationSnapshotRepository, never())
                .saveAll(anyList());
    }

    @Test
    void shouldIncludeEventArrivalsInSavedSnapshots() {
        Organizer organizer = new Organizer();
        organizer.setId(1);

        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setOrganizer(organizer);
        simulation.setStatus("PENDING");
        simulation.setDurationMinutes(40);
        simulation.setRandomSeed(42L);

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Exhibition Hall");
        zone.setCapacity(100);
        zone.setInitialVisitors(0);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);
        when(zoneRepository.findAll()).thenReturn(List.of(zone));
        when(zoneConnectionRepository.findAll()).thenReturn(List.of());

        when(expoEventRepository.findExpoEventsByOrganizer_Id(1))
                .thenAnswer(invocation -> {
                    LocalDateTime start = simulation.getStartDateTime();

                    return List.of(createEvent(
                            1,
                            "Technology Expo",
                            zone,
                            10,
                            start.plusMinutes(30),
                            start.plusMinutes(60)
                    ));
                });

        when(simulationSnapshotRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<SimulationSnapshot> snapshots =
                simulationService.runSimulation(1);

        assertNotNull(snapshots);
        assertEquals(41, snapshots.size()); // Minutes 0 through 40

        int finalOccupancy = snapshots.stream()
                .filter(snapshot -> snapshot.getMinute() == 40)
                .filter(snapshot -> snapshot.getZone().getId().equals(1))
                .mapToInt(SimulationSnapshot::getOccupancy)
                .sum();

        assertEquals(10, finalOccupancy);

        verify(simulationSnapshotRepository).saveAll(anyList());
    }

    @Test
    void shouldIgnoreEventsOutsideSimulationWindow() {
        Organizer organizer = new Organizer();
        organizer.setId(1);

        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setOrganizer(organizer);
        simulation.setStatus("PENDING");
        simulation.setDurationMinutes(40);
        simulation.setRandomSeed(42L);

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Exhibition Hall");
        zone.setCapacity(100);
        zone.setInitialVisitors(0);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);
        when(zoneRepository.findAll()).thenReturn(List.of(zone));
        when(zoneConnectionRepository.findAll()).thenReturn(List.of());

        when(expoEventRepository.findExpoEventsByOrganizer_Id(1))
                .thenAnswer(invocation -> {
                    LocalDateTime start = simulation.getStartDateTime();

                    return List.of(createEvent(
                            1,
                            "Outside Event",
                            zone,
                            10,
                            start.plusMinutes(50),
                            start.plusMinutes(60)
                    ));
                });

        when(simulationSnapshotRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<SimulationSnapshot> snapshots =
                simulationService.runSimulation(1);

        int finalOccupancy = snapshots.stream()
                .filter(snapshot -> snapshot.getMinute() == 40)
                .filter(snapshot -> snapshot.getZone().getId().equals(1))
                .mapToInt(SimulationSnapshot::getOccupancy)
                .sum();

        assertEquals(0, finalOccupancy);
        assertEquals(41, snapshots.size());
    }

    @Test
    void shouldIncludeEventOverlappingSimulationEnd() {
        Organizer organizer = new Organizer();
        organizer.setId(1);

        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setOrganizer(organizer);
        simulation.setStatus("PENDING");
        simulation.setDurationMinutes(40);
        simulation.setRandomSeed(42L);

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Exhibition Hall");
        zone.setCapacity(100);
        zone.setInitialVisitors(0);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);
        when(zoneRepository.findAll()).thenReturn(List.of(zone));
        when(zoneConnectionRepository.findAll()).thenReturn(List.of());

        when(expoEventRepository.findExpoEventsByOrganizer_Id(1))
                .thenAnswer(invocation -> {
                    LocalDateTime start = simulation.getStartDateTime();

                    return List.of(createEvent(
                            1,
                            "Overlapping Event",
                            zone,
                            10,
                            start.plusMinutes(35),
                            start.plusMinutes(60)
                    ));
                });

        when(simulationSnapshotRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<SimulationSnapshot> snapshots =
                simulationService.runSimulation(1);

        assertEquals(41, snapshots.size());

        int finalOccupancy = snapshots.stream()
                .filter(snapshot -> snapshot.getMinute() == 40)
                .filter(snapshot -> snapshot.getZone().getId().equals(1))
                .mapToInt(SimulationSnapshot::getOccupancy)
                .sum();

        assertEquals(10, finalOccupancy);
    }




    // ------------------------ Helper -------------------------
    private ExpoEvent createEvent(
            int id,
            String name,
            Zone zone,
            int demand,
            LocalDateTime start,
            LocalDateTime end) {

        ExpoEvent event = new ExpoEvent();
        event.setId(id);
        event.setName(name);
        event.setZone(zone);
        event.setExpectedDemand(demand);
        event.setStartDateTime(start);
        event.setEndDateTime(end);

        return event;
    }



    @Test
    void shouldRejectContinuationWhenNoSnapshotsExist() {
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("COMPLETED");

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);

        when(simulationSnapshotRepository.findBySimulation_Id(1))
                .thenReturn(List.of());

        assertThrows(
                ApiException.class,
                () -> simulationService.continueSimulation(1, 10)
        );

        verify(simulationSnapshotRepository)
                .findBySimulation_Id(1);

        verify(simulationSnapshotRepository, never())
                .saveAll(anyList());
    }



    @Test
    void shouldRejectContinuationWhenSimulationIsNotCompleted() {
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("PENDING");

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);

        assertThrows(
                ApiException.class,
                () -> simulationService.continueSimulation(1, 10)
        );

        verify(simulationSnapshotRepository, never())
                .findBySimulation_Id(1);

        verify(simulationSnapshotRepository, never())
                .saveAll(anyList());
    }



    @Test
    void shouldRejectContinuationWithInvalidDuration() {
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("COMPLETED");

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);

        assertThrows(
                ApiException.class,
                () -> simulationService.continueSimulation(1, 0)
        );

        verify(simulationSnapshotRepository, never())
                .findBySimulation_Id(1);
    }



    @Test
    void shouldRejectContinuationWhenTotalDurationExceedsLimit() {
        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setStatus("COMPLETED");
        simulation.setDurationMinutes(600);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);

        SimulationSnapshot snapshot = new SimulationSnapshot();
        snapshot.setMinute(600);
        snapshot.setOccupancy(10);
        snapshot.setQueueLength(0);

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Main Hall");
        zone.setCapacity(100);
        snapshot.setZone(zone);

        when(simulationSnapshotRepository.findBySimulation_Id(1))
                .thenReturn(List.of(snapshot));

        assertThrows(
                ApiException.class,
                () -> simulationService.continueSimulation(1, 1)
        );

        verify(simulationSnapshotRepository, never())
                .saveAll(anyList());
    }



    @Test
    void shouldContinueSimulationFromLastSavedSnapshot() {
        // Arrange
        Organizer organizer = new Organizer();
        organizer.setId(1);

        LocalDateTime start = LocalDateTime.of(2026, 10, 15, 10, 0);

        Simulation simulation = new Simulation();
        simulation.setId(1);
        simulation.setOrganizer(organizer);
        simulation.setStatus("COMPLETED");
        simulation.setDurationMinutes(2);
        simulation.setStartDateTime(start);
        simulation.setRandomSeed(42L);

        Zone zone = new Zone();
        zone.setId(1);
        zone.setName("Main Hall");
        zone.setCapacity(100);
        zone.setInitialVisitors(10);

        SimulationSnapshot lastSnapshot = new SimulationSnapshot();
        lastSnapshot.setSimulation(simulation);
        lastSnapshot.setZone(zone);
        lastSnapshot.setMinute(2);
        lastSnapshot.setOccupancy(20);
        lastSnapshot.setQueueLength(0);

        when(simulationRepository.findSimulationById(1))
                .thenReturn(simulation);

        when(simulationSnapshotRepository.findBySimulation_Id(1))
                .thenReturn(List.of(lastSnapshot));

        when(zoneRepository.findAll())
                .thenReturn(List.of(zone));

        when(zoneConnectionRepository.findAll())
                .thenReturn(List.of());

        when(expoEventRepository.findExpoEventsByOrganizer_Id(1))
                .thenReturn(List.of());

        when(simulationSnapshotRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<SimulationSnapshot> newSnapshots =
                simulationService.continueSimulation(1, 2);

        // Assert
        assertEquals(2, newSnapshots.size());

        assertEquals(3, newSnapshots.get(0).getMinute());
        assertEquals(4, newSnapshots.get(1).getMinute());

        assertEquals(20, newSnapshots.get(0).getOccupancy());
        assertEquals(20, newSnapshots.get(1).getOccupancy());

        assertEquals("COMPLETED", simulation.getStatus());
        assertEquals(4, simulation.getDurationMinutes());

        verify(simulationSnapshotRepository).saveAll(anyList());
        verify(simulationRepository, times(2)).save(simulation);
    }






}
