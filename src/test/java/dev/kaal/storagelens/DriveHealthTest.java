package dev.kaal.storagelens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.io.IOException;
import java.math.*;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DriveHealthTest {
    final ObjectMapper json = new ObjectMapper();
    ObjectNode fixture() throws Exception { return (ObjectNode) json.readTree("""
        {"controller":"nvme0","model":"Fixture NVMe","readable":true,"percentage_used":25,"available_spare":100,
         "critical_warning":0,"temperature_kelvin":300,"data_units_written":"10000000","host_write_commands":"123456789"}
        """); }
    @Test void percentageAndWriteBudgetUseTheSmartUnitsAndDecimalTerabytes() throws Exception {
        var health = DriveHealthService.parse(fixture()); assertEquals(75, health.remainingPercent());
        assertEquals(new BigInteger("5120000000000"), health.writtenBytes()); assertEquals("5.12 TB", DriveHealthService.tb(health.writtenBytes()));
        assertEquals(new BigDecimal("74.880000000000"), health.remainingTbw(new BigDecimal("80"))); assertEquals(new BigDecimal("26.85"), health.temperatureC());
    }
    @Test void unavailableSmartNeverAppearsAsOneHundredPercentLife() throws Exception {
        var node = fixture().put("readable", false).put("errno", 13).put("error", "Permission denied");
        var health = DriveHealthService.parse(node); assertNull(health.remainingPercent()); assertNull(health.writtenBytes()); assertNull(health.remainingTbw(new BigDecimal("80"))); assertTrue(health.needsPermission());
    }
    @Test void enduranceAboveOneHundredAndExceededRatingDoNotBecomeNegative() throws Exception {
        var health = DriveHealthService.parse(fixture().put("percentage_used", 125)); assertEquals(0, health.remainingPercent());
        assertEquals(BigDecimal.ZERO, health.remainingTbw(BigDecimal.ONE));
    }
    @Test void unknownRatingAndZeroUnsupportedWriteCountersStayUnavailable() throws Exception {
        var health = DriveHealthService.parse(fixture().put("data_units_written", "0"));
        assertNull(health.writtenBytes()); assertNull(health.remainingTbw(new BigDecimal("80")));
        assertNull(DriveHealthService.parse(fixture()).remainingTbw(null));
    }
    @Test void unsignedOneHundredTwentyEightBitCountersDoNotOverflow() throws Exception {
        String max = "340282366920938463463374607431768211455";
        assertEquals(new BigInteger(max).multiply(BigInteger.valueOf(512000)), DriveHealthService.parse(fixture().put("data_units_written", max)).writtenBytes());
        assertThrows(IOException.class, () -> DriveHealthService.parse(fixture().put("data_units_written", new BigInteger(max).add(BigInteger.ONE).toString())));
    }
    @Test void missingAndInvalidSmartCountersAreRejected() throws Exception {
        var missing = fixture(); missing.remove("percentage_used"); assertThrows(IOException.class, () -> DriveHealthService.parse(missing));
        assertThrows(IOException.class, () -> DriveHealthService.parse(fixture().put("percentage_used", -1)));
    }
    @Test void readerCommandsAcceptOnlyControllerNamesAndFixedReadOperation() throws Exception {
        Path helper = Path.of("/fixture/storage-lens-nvme");
        assertEquals(List.of("/usr/bin/pkexec", helper.toString(), "--read", "nvme0"), DriveHealthService.command(helper, "nvme0", true));
        for (String invalid : List.of("../sda", "/dev/nvme0", "nvme0n1", "nvme0;rm")) assertThrows(IOException.class, () -> DriveHealthService.command(helper, invalid, false));
    }
    @Test void actualHealthPanelDisplaysExplicitlyUnknownAndCalculatedValues() throws Exception {
        var available = DriveHealthService.parse(fixture()); var unavailable = DriveHealthService.parse(fixture().put("readable", false).put("errno", 13));
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); DriveHealthPanel panel = new DriveHealthPanel(null, (value, rating) -> {});
            panel.setHealth(available, new BigDecimal("80")); assertEquals("75%", panel.life.getText()); assertEquals("74.88 TB", panel.remaining.getText());
            panel.setHealth(unavailable, new BigDecimal("80")); assertEquals("Unavailable", panel.life.getText()); assertEquals("Unavailable", panel.written.getText()); assertEquals("SMART needed", panel.remaining.getText());
        });
    }
}
