package com.ylcloud.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceModeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void markerPersistsMaintenanceModeAcrossServiceRestart() {
        MaintenanceModeService first = new MaintenanceModeService(tempDir.toString());
        first.enableMaintenanceMode("upgrade");

        assertTrue(first.isMaintenanceMode());
        assertTrue(Files.exists(tempDir.resolve("maintenance-mode")));

        MaintenanceModeService restarted = new MaintenanceModeService(tempDir.toString());
        assertTrue(restarted.isMaintenanceMode());

        restarted.disableMaintenanceMode();
        assertFalse(restarted.isMaintenanceMode());
        assertFalse(Files.exists(tempDir.resolve("maintenance-mode")));
    }

    @Test
    void failedMarkerWriteDoesNotClaimMaintenanceIsEnabled() throws Exception {
        Path invalidRoot = tempDir.resolve("not-a-directory");
        Files.writeString(invalidRoot, "occupied");
        MaintenanceModeService service = new MaintenanceModeService(invalidRoot.toString());

        assertThrows(IllegalStateException.class,
                () -> service.enableMaintenanceMode("upgrade"));
        assertFalse(service.getStatus().active());
    }
}
