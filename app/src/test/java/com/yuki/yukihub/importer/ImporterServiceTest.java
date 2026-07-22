package com.yuki.yukihub.importer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImporterServiceTest {
    @Test
    public void parseIsoTime_parsesUtcZuluTime() {
        assertEquals(0L, ImporterService.parseIsoTime("1970-01-01T00:00:00Z"));
    }

    @Test
    public void parseIsoTime_parsesExplicitOffset() {
        assertEquals(
                ImporterService.parseIsoTime("2026-07-22T08:00:00Z"),
                ImporterService.parseIsoTime("2026-07-22T16:00:00+08:00"));
    }
}
