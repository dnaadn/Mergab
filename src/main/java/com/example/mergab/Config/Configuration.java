package com.example.mergab.Config;


import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@EnableWebSecurity
@org.springframework.context.annotation.Configuration
public class Configuration {

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception{
        return http.csrf(csrf -> csrf.disable())

                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/visitor/register", "/api/v1/prayer/today",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",

                                // Public read-only APIs
                                "/api/v1/zone/get",
                                "/api/v1/zone/get-by-id/{id}",
                                "/api/v1/zone/get-available",
                                "/api/v1/zone/get-occupancy/{id}",
                                "/api/v1/zone-visit/occupancy/{zoneId}",
                                "/api/v1/zone-connection/get",
                                "/api/v1/expo-event/get",
                                "/api/v1/expo-event/get-upcoming",
                                "/api/v1/expo-event/get-today",

                                "/api/v1/expo-event/get-by-id/{id}",
                                "/api/v1/expo-event/get-by-zone/{zoneId}",
                                "/api/v1/zone/get-zone-by-name/{name}",
                                "/api/v1/zone-connection/get-by-id/{id}",
                                "/api/v1/zone-connection/get-outgoing/{zoneId}",
                                "/api/v1/zone-connection/get-by-zone/{zoneId}",
                                "/api/v1/facility/get",
                                "/api/v1/facility/get-by-id/{id}",
                                "/api/v1/facility/get-by-zone/{zoneId}",

                                "/api/v1/zone-walk-path/get-active-by-zone/{zoneId}",
                                "/api/v1/facility-access-path/get-active-by-facility/{facilityId}",



                                // Public pages
                                "/",
                                "/login",
                                "/register",
                                "/privacy",
                                "/forbidden",
                                "/visitor/map",
                                "/visitor/events",
                                "/admin",
                                "/admin/users",
                                "/account",
                                "/organizer/dashboard",
                                "/organizer/zones",
                                "/organizer/connections",
                                "/organizer/events",
                                "/organizer/simulations",
                                "/organizer/reports",
                                "/organizer/check-in",
                                "/visitor/visit",
                                "/visitor/account",
                                "/visitor",


                                // Static resources
                                "/css/**",
                                "/js/**",
                                "/img/**",
                                "/images/**",
                                "/map/**",
                                "/assets/**"
                           )
                        .permitAll()

                        // Admin only
                        .requestMatchers(
                                "/api/v1/user/get-all",
                                "/api/v1/user/get/{id}",
                                "/api/v1/user/get-by-role/{role}",
                                "/api/v1/user/add-admin",
                                "/api/v1/user/delete/{id}",
                                "/api/v1/organizer/get-all",
                                "/api/v1/organizer/delete/{id}",
                                "/api/v1/organizer/register",
                                "/api/v1/visitor/get-all"
                        ).hasAuthority("ADMIN")

                        .requestMatchers("/api/v1/organizer/update-my-phone",
                                "/api/v1/expo-event/add/{zoneId}",
                                "/api/v1/expo-event/update/{id}",
                                "/api/v1/expo-event/change-zone/{eventId}/{zoneId}",
                                "/api/v1/expo-event/delete/{id}",
                                "/api/v1/simulation/get-all",
                                "/api/v1/simulation/get-by-organizer/{organizerId}",
                                "/api/v1/simulation/add/{historicalAttendanceId}",
                                "/api/v1/simulation/run/{id}",
                                "/api/v1/simulation/update/{id}",
                                "/api/v1/simulation/delete/{id}",
                                "/api/v1/simulation/get-by-id/{id}",
                                "/api/v1/simulation/get-by-status/{status}",
                                "/api/v1/simulation-snapshot/get",
                                "/api/v1/simulation-snapshot/add/{simulationId}/{zoneId}",
                                "/api/v1/simulation-snapshot/update/{id}",
                                "/api/v1/simulation-snapshot/change-zone/{snapshotId}/{zoneId}",
                                "/api/v1/simulation-snapshot/delete/{id}",
                                "/api/v1/simulation-snapshot/get-by-id/{id}",
                                "/api/v1/simulation-snapshot/get-by-zone/{zoneId}",
                                "/api/v1/simulation-snapshot/get-by-zone-ordered/{zoneId}",
                                "/api/v1/simulation-snapshot/get-by-simulation/{simulationId}",
                                "/api/v1/simulation-snapshot/get-by-simulation-and-zone/{simulationId}/{zoneId}",
                                "/api/v1/simulation-snapshot/get-at-minute/{simulationId}/{minute}",
                                "/api/v1/simulation-snapshot/get-peak-occupancy/{simulationId}/{zoneId}",
                                "/api/v1/attendance/get-all",
                                "/api/v1/attendance/add",
                                "/api/v1/attendance/update/{id}",
                                "/api/v1/attendance/delete/{id}",
                                "/api/v1/attendance/get-by-id/{id}",
                                "/api/v1/attendance/get-by-expo/{expoName}",
                                "/api/v1/zone/add",
                                "/api/v1/zone/update/{zoneId}",
                                "/api/v1/zone/delete/{zoneId}",
                                "/api/v1/zone-connection/add/{fromZoneId}/{toZoneId}",
                                "/api/v1/zone-connection/update/{id}",
                                "/api/v1/zone-connection/change-zones/{id}/{fromZoneId}/{toZoneId}",
                                "/api/v1/zone-connection/delete/{id}",
                                "/api/v1/zone-visit/check-in/{zoneId}",
                                "/api/v1/zone-visit/check-out/{zoneId}",
                                "/api/v1/zone-visit/history/{visitorId}",
                                "/api/v1/zone-visit/active/{zoneId}",
                                "/api/v1/zone-visit/manual-check-out",
                                "/api/v1/zone-visit/active-by-qr",
                                "/api/v1/intervention/get-all",
                                "/api/v1/intervention/add/{simulationId}/{sourceZoneId}/{targetZoneId}",
                                "/api/v1/intervention/update/{id}/{sourceZoneId}/{targetZoneId}",
                                "/api/v1/intervention/delete/{id}",
                                "/api/v1/intervention/get-by-id/{id}",
                                "/api/v1/intervention/get-by-simulation/{simulationId}",
                                "/api/v1/facility/add/{zoneId}",
                                "/api/v1/facility/update/{id}",
                                "/api/v1/facility/delete/{id}",
                                "/api/v1/ai/predict/{simulationId}/{zoneId}",
                                "/api/v1/ai/analyze/{simulationId}/{zoneId}",
                                "/api/v1/ai/early-warnings/{simulationId}",
                                "/api/v1/zone-walk-path/**",
                                "/api/v1/facility-access-path/**",
                                "/api/v1/simulation/continue/{id}",
                                "/api/v1/whatsapp/test")
                        .hasAuthority("ORGANIZER")


                        .requestMatchers(
                                "/api/v1/visitor/update-my-info",
                                "/api/v1/visitor/my-qr-code",
                                "/api/v1/prayer/my-alert",
                                "/api/v1/prayer/my-alert/seen/{prayer}",
                                "/api/v1/zone-visit/my-history"
                        ).hasAuthority("VISITOR")

                        .requestMatchers(
                                "/api/v1/user/my-account",
                                "/api/v1/user/update-my-account",
                                "/api/v1/user/change-password",
                                "/api/v1/user/delete-my-account",
                                "/api/v1/expo-event/get-by-organizer/{organizerId}",
                                "/api/v1/expo-event/route-to-event/{eventId}/{fromZoneId}",
                                "/api/v1/facility/nearest",
                                "/api/v1/facility/nearest-map"
                        ).hasAnyAuthority("ADMIN", "ORGANIZER", "VISITOR")

                        .anyRequest().authenticated()
                )

                .httpBasic(basic -> {})
                .build();


    }
}
