package com.example.mergab.Service;

import com.example.mergab.Model.*;
import com.example.mergab.Repository.*;
import com.example.mergab.Simulation.SimulationSnapshotRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;

// كل إشعارات الواتساب في مكان واحد (للزوار والمنظمين)
@Service
@RequiredArgsConstructor
public class NotificationService {

    // نسبة الإشغال اللي نعتبرها "زحمة"
    private static final int CROWDED_PERCENT = 80;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM hh:mm a");

    private final WhatsAppService whatsAppService;
    private final OrganizerRepository organizerRepository;
    private final ZoneVisitRepository zoneVisitRepository;
    private final ZoneConnectionRepository zoneConnectionRepository;
    private final FacilityRepository facilityRepository;

    // ============ الحسابات ============

    public void visitorWelcome(String phone, String fullName) {
        whatsAppService.sendMessage(phone,
                "أهلاً " + fullName + " 👋\n"
                        + "تم إنشاء حسابك في مِرقاب بنجاح.\n"
                        + "تذكرتك (QR) وصلتك على الإيميل، وبتوصلك هنا تنبيهات الزحمة والفعاليات أثناء زيارتك.\n\n"
                        + "Welcome to Mirqab! Your account is ready.");
    }

    public void organizerWelcome(String phone, String fullName) {
        whatsAppService.sendMessage(phone,
                "أهلاً " + fullName + " 👋\n"
                        + "تم تسجيلك كمنظم في مِرقاب.\n"
                        + "بتوصلك هنا تنبيهات امتلاء المناطق ونتائج المحاكاة.\n\n"
                        + "You are now registered as a Mirqab organizer.");
    }

    public void passwordResetCode(User user, String code) {
        String phone = phoneOf(user);
        if (phone == null) return;

        whatsAppService.sendMessage(phone,
                "رمز استعادة كلمة المرور في مِرقاب: " + code + "\n"
                        + "لا تشارك الرمز مع أحد.\n\n"
                        + "Your Mirqab password reset code: " + code);
    }

    // ============ دخول المناطق والإشغال ============

    // تأكيد دخول الزائر للمنطقة
    public void visitorCheckedIn(Visitor visitor, Zone zone, long occupancy) {
        int percent = percent(occupancy, zone.getCapacity());

        String msg = "✅ دخلت " + zone.getName() + "\n"
                + "الإشغال الحالي: " + percent + "%";

        if (percent >= CROWDED_PERCENT) {
            Zone alternative = findAlternative(zone);
            msg += "\n⚠️ المنطقة مزدحمة حالياً";
            if (alternative != null) {
                msg += "، ننصحك بزيارة " + alternative.getName() + " (أقل زحمة)";
            }
        }

        whatsAppService.sendMessage(visitor.getPhoneNumber(), msg);
    }

    // الزائر حاول يدخل والمنطقة فل
    public void zoneFullForVisitor(Visitor visitor, Zone zone) {
        Zone alternative = findAlternative(zone);

        String msg = "⛔ " + zone.getName() + " ممتلئة حالياً ولا يمكن الدخول.";
        if (alternative != null) {
            msg += "\nجرّب " + alternative.getName() + " القريبة، الإشغال فيها "
                    + percent(currentOccupancy(alternative), alternative.getCapacity()) + "%.";
        }
        msg += "\n\n" + zone.getName() + " is full right now.";

        whatsAppService.sendMessage(visitor.getPhoneNumber(), msg);
    }

    // ينادى بعد كل دخول: يرسل للمنظمين لما المنطقة توصل 80% أو تمتلئ (مرة وحدة عند الوصول)
    public void checkZoneThresholds(Zone zone, long before, long after) {
        int capacity = zone.getCapacity();

        if (after >= capacity && before < capacity) {
            notifyOrganizers("🔴 تنبيه امتلاء: " + zone.getName() + "\n"
                    + "المنطقة وصلت للسعة القصوى (" + after + "/" + capacity + ").\n"
                    + "تم إيقاف الدخول تلقائياً، يُنصح بتوجيه الزوار لمنطقة ثانية.");
            return;
        }

        if (percent(after, capacity) >= CROWDED_PERCENT && percent(before, capacity) < CROWDED_PERCENT) {
            notifyOrganizers("🟠 تنبيه زحمة: " + zone.getName() + "\n"
                    + "الإشغال وصل " + percent(after, capacity) + "% (" + after + "/" + capacity + ").");
        }
    }

    // ============ الفعاليات ============

    public void eventUpdated(ExpoEvent event) {
        notifyVisitorsInside("📢 تحديث على فعالية \"" + event.getName() + "\"\n"
                + "الموعد الجديد: " + event.getStartDateTime().format(DATE_TIME)
                + " - " + event.getEndDateTime().format(TIME) + "\n"
                + "المكان: " + event.getZone().getName());
    }

    public void eventZoneChanged(ExpoEvent event, String oldZoneName) {
        notifyVisitorsInside("📍 تغيّر مكان فعالية \"" + event.getName() + "\"\n"
                + "من " + oldZoneName + " إلى " + event.getZone().getName());
    }

    public void eventCancelled(String eventName, String zoneName) {
        notifyVisitorsInside("❌ تم إلغاء فعالية \"" + eventName + "\" في " + zoneName + ".\nنعتذر عن الإزعاج.");
    }

    public void eventStartingSoon(ExpoEvent event, long minutes) {
        notifyVisitorsInside("⏰ فعالية \"" + event.getName() + "\" تبدأ بعد " + minutes + " دقيقة\n"
                + "المكان: " + event.getZone().getName() + "\n"
                + "الوقت: " + event.getStartDateTime().format(TIME));
    }

    // ============ الصلاة ============

    public void prayerReminder(String prayerNameAr, String time, long minutes) {
        for (ZoneVisit visit : zoneVisitRepository.findByCheckOutTimeIsNull()) {
            Visitor visitor = visit.getVisitor();
            if (!Boolean.TRUE.equals(visitor.getShowPrayerTimes())) continue;

            String msg = "🕌 صلاة " + prayerNameAr + " بعد " + minutes + " دقيقة (" + time + ")";

            Facility room = findPrayerRoom(visit.getZone(), visitor.getGender());
            if (room != null) {
                msg += "\nأقرب مصلى لك: " + room.getName();
            }

            whatsAppService.sendMessage(visitor.getPhoneNumber(), msg);
        }
    }

    // ============ المحاكاة ============

    // ملخص للمنظم بعد ما تخلص المحاكاة: المناطق اللي توقعنا فيها زحمة
    public void simulationCompleted(Simulation simulation, List<Zone> zones, List<SimulationSnapshotRecord> records) {
        if (simulation.getOrganizer() == null) return;

        Map<Integer, SimulationSnapshotRecord> peaks = new HashMap<>();
        for (SimulationSnapshotRecord record : records) {
            SimulationSnapshotRecord peak = peaks.get(record.zoneId());
            if (peak == null || record.occupancy() > peak.occupancy()) {
                peaks.put(record.zoneId(), record);
            }
        }

        StringBuilder msg = new StringBuilder("📊 اكتملت المحاكاة رقم " + simulation.getId()
                + " (" + simulation.getDurationMinutes() + " دقيقة)\n");

        int crowded = 0;
        for (Zone zone : zones) {
            SimulationSnapshotRecord peak = peaks.get(zone.getId());
            if (peak == null) continue;

            int percent = percent(peak.occupancy(), zone.getCapacity());
            if (percent >= CROWDED_PERCENT) {
                crowded++;
                msg.append("\n• ").append(zone.getName()).append(": ").append(percent)
                        .append("% عند الدقيقة ").append(peak.minute());
            }
        }

        if (crowded == 0) {
            msg.append("\nما فيه مناطق متوقع تتجاوز ").append(CROWDED_PERCENT).append("% ✅");
        } else {
            msg.insert(msg.indexOf("\n") + 1, "\n⚠️ مناطق متوقع فيها زحمة:");
        }

        whatsAppService.sendMessage(simulation.getOrganizer().getPhoneNumber(), msg.toString());
    }

    // ============ أدوات مساعدة ============

    private void notifyOrganizers(String message) {
        for (Organizer organizer : organizerRepository.findAll()) {
            whatsAppService.sendMessage(organizer.getPhoneNumber(), message);
        }
    }

    // للزوار اللي داخل المعرض الحين (عندهم دخول بدون خروج)
    private void notifyVisitorsInside(String message) {
        for (ZoneVisit visit : zoneVisitRepository.findByCheckOutTimeIsNull()) {
            whatsAppService.sendMessage(visit.getVisitor().getPhoneNumber(), message);
        }
    }

    // أقل منطقة زحمة من المناطق المتصلة بهذي المنطقة
    private Zone findAlternative(Zone zone) {
        Zone best = null;
        int bestPercent = CROWDED_PERCENT;

        for (ZoneConnection connection : zoneConnectionRepository.findByFromZone_Id(zone.getId())) {
            Zone next = connection.getToZone();
            int p = percent(currentOccupancy(next), next.getCapacity());
            if (p < bestPercent) {
                best = next;
                bestPercent = p;
            }
        }
        return best;
    }

    private Facility findPrayerRoom(Zone zone, String gender) {
        for (Facility facility : facilityRepository.findFacilitiesByZone_Id(zone.getId())) {
            if ("PRAYER_ROOM".equals(facility.getType())
                    && ("BOTH".equals(facility.getGender()) || facility.getGender().equals(gender))) {
                return facility;
            }
        }
        return null;
    }

    private long currentOccupancy(Zone zone) {
        return zoneVisitRepository.countByZone_IdAndCheckOutTimeIsNull(zone.getId());
    }

    private String phoneOf(User user) {
        if (user.getVisitor() != null) return user.getVisitor().getPhoneNumber();
        if (user.getOrganizer() != null) return user.getOrganizer().getPhoneNumber();
        return null;
    }

    private int percent(long value, Integer capacity) {
        if (capacity == null || capacity <= 0) return 0;
        return (int) Math.round(value * 100.0 / capacity);
    }
}
