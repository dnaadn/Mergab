package com.example.mergab.Service;

import com.example.mergab.Model.ExpoEvent;
import com.example.mergab.Repository.ExpoEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// تنبيهات تلقائية كل دقيقة: الصلاة القادمة والفعاليات اللي بتبدأ قريب
@Service
@RequiredArgsConstructor
public class NotificationScheduler {

    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final int PRAYER_MINUTES_BEFORE = 15;
    private static final int EVENT_MINUTES_BEFORE = 30;

    private static final Map<String, String> PRAYER_NAMES = Map.of(
            "fajr", "الفجر", "dhuhr", "الظهر", "asr", "العصر",
            "maghrib", "المغرب", "isha", "العشاء");

    private final NotificationService notificationService;
    private final ExpoEventRepository expoEventRepository;
    private final PrayerTimesService prayerTimesService;

    // عشان ما نرسل نفس التنبيه مرتين
    private final Set<String> sentPrayers = new HashSet<>();
    private final Set<Integer> sentEvents = new HashSet<>();

    @Scheduled(fixedRate = 60_000)
    public void checkPrayerTimes() {
        LocalDate today = LocalDate.now(RIYADH);
        int now = LocalTime.now(RIYADH).getHour() * 60 + LocalTime.now(RIYADH).getMinute();

        for (Map.Entry<String, String> prayer : prayerTimesService.getTodayTimes().entrySet()) {
            int left = PrayerTimesService.toMin(prayer.getValue()) - now;
            String key = today + "-" + prayer.getKey();

            if (left > 0 && left <= PRAYER_MINUTES_BEFORE && !sentPrayers.contains(key)) {
                sentPrayers.add(key);
                notificationService.prayerReminder(
                        PRAYER_NAMES.get(prayer.getKey()), prayer.getValue(), left);
            }
        }
    }

    @Scheduled(fixedRate = 60_000)
    public void checkUpcomingEvents() {
        LocalDateTime now = LocalDateTime.now(RIYADH);

        for (ExpoEvent event : expoEventRepository.findByEndDateTimeAfterOrderByStartDateTimeAsc(now)) {
            long left = Duration.between(now, event.getStartDateTime()).toMinutes();

            if (left > 0 && left <= EVENT_MINUTES_BEFORE && !sentEvents.contains(event.getId())) {
                sentEvents.add(event.getId());
                notificationService.eventStartingSoon(event, left);
            }
        }
    }
}
