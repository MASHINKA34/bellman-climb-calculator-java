package ru.mashinka.multiflow.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// часы для тестов: идут, только когда их двигают
public class TestClock extends Clock {

    private Instant now = Instant.parse("2026-11-25T10:00:00Z");

    public void advance(Duration step) {
        now = now.plus(step);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
