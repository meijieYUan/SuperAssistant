package com.itajay.superassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
public class DateTimeTool {

    private static final Logger log = LoggerFactory.getLogger(DateTimeTool.class);

    @Tool(description = "Get the current date and time, with timezone and day of week")
    public String getCurrentDateTime() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        return String.format("Current date and time:\n  Date: %s\n  Time: %s\n  Day of week: %s\n  Timezone: %s\n  Unix timestamp: %d",
                now.format(DateTimeFormatter.ISO_LOCAL_DATE),
                now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                now.getDayOfWeek(),
                now.getZone(),
                now.toEpochSecond());
    }

    @Tool(description = "Calculate the difference between two dates in yyyy-MM-dd format")
    public String dateDifference(
            @ToolParam(description = "First date in yyyy-MM-dd format") String date1,
            @ToolParam(description = "Second date in yyyy-MM-dd format") String date2) {
        try {
            LocalDate d1 = LocalDate.parse(date1);
            LocalDate d2 = LocalDate.parse(date2);
            Period period = Period.between(d1, d2);
            long totalDays = ChronoUnit.DAYS.between(d1, d2);
            return String.format("Between %s and %s:\n  %d years, %d months, %d days\n  Total: %d days",
                    date1, date2,
                    Math.abs(period.getYears()), Math.abs(period.getMonths()), Math.abs(period.getDays()),
                    Math.abs(totalDays));
        } catch (Exception e) {
            return "Date parse error. Use yyyy-MM-dd format. Error: " + e.getMessage();
        }
    }

    @Tool(description = "Add or subtract days/weeks/months/years from a date")
    public String dateAdd(
            @ToolParam(description = "Base date yyyy-MM-dd, or 'today'") String base,
            @ToolParam(description = "Amount to add, negative to subtract") int amount,
            @ToolParam(description = "Unit: DAYS, WEEKS, MONTHS, YEARS") String unit) {
        try {
            LocalDate date = base.equalsIgnoreCase("today")
                    ? LocalDate.now() : LocalDate.parse(base);
            LocalDate result = switch (unit.toUpperCase()) {
                case "DAYS" -> date.plusDays(amount);
                case "WEEKS" -> date.plusWeeks(amount);
                case "MONTHS" -> date.plusMonths(amount);
                case "YEARS" -> date.plusYears(amount);
                default -> throw new IllegalArgumentException("Unknown unit: " + unit);
            };
            return String.format("%s %+d %s = %s (%s)",
                    date, amount, unit.toLowerCase(), result, result.getDayOfWeek());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}