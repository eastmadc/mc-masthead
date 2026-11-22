package io.github.eastmadc.masthead.construction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/** Which paper a moment belongs to: edition D covers [D-1 at the edition time, D at the edition time). */
public final class EditionWindow {
    private EditionWindow() {}

    public static LocalDate dateFor(Instant now, LocalTime time, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(now, zone);
        return t.toLocalTime().isBefore(time) ? t.toLocalDate() : t.toLocalDate().plusDays(1);
    }
}
