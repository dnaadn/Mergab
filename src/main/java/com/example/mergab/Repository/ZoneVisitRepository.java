package com.example.mergab.Repository;


import com.example.mergab.Model.ZoneVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ZoneVisitRepository extends JpaRepository<ZoneVisit, Integer> {

    // Find the visitor's active visit
    ZoneVisit findByVisitor_IdAndCheckOutTimeIsNull(Integer visitorId);

    // Count visitors currently inside a zone
    long countByZone_IdAndCheckOutTimeIsNull(Integer zoneId);

    // Get visitor's visit history
    List<ZoneVisit> findByVisitor_Id(Integer visitorId);

    // Check if a visitor has any visit history
    boolean existsByVisitor_Id(Integer visitorId);

    // Check if a zone has any visit history
    boolean existsByZone_Id(Integer zoneId);

    List<ZoneVisit> findByZone_IdAndCheckOutTimeIsNull(Integer zoneId);

    // All visitors currently inside the expo
    List<ZoneVisit> findByCheckOutTimeIsNull();

    @Modifying
    @Query("DELETE FROM ZoneVisit z WHERE z.visitor.id = :visitorId")
    int deleteVisitsByVisitorId(@Param("visitorId") Integer visitorId);
}