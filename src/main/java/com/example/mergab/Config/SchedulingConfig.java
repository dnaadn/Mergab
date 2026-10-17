package com.example.mergab.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// يشغّل المهام المجدولة (تنبيهات الصلاة والفعاليات)
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
