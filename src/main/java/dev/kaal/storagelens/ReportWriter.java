package dev.kaal.storagelens;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;
import static dev.kaal.storagelens.Models.*;

public final class ReportWriter {
    private ReportWriter() {}
    public static void write(Path destination, ScanResult scan, AppInventory inventory) throws IOException {
        // CREATE_NEW keeps an export from overwriting an existing file or following a link.
        try (Writer out = Files.newBufferedWriter(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write("record,path_or_package,logical_bytes,allocated_bytes,category_or_source,modified_or_version,review_note\n");
            Map<Path, Finding> unique = new LinkedHashMap<>();
            if (scan != null) {
                for (Finding finding : scan.cleanup()) unique.put(finding.path(), finding);
                for (Finding finding : scan.large()) unique.putIfAbsent(finding.path(), finding);
                for (Finding f : unique.values()) row(out, "file", f.path().toString(), Long.toString(f.bytes()), Long.toString(f.allocated()), f.kind().label, f.modified().toString(), f.note());
                for (Folder folder : scan.folders()) row(out, "folder", folder.path().toString(), "", Long.toString(folder.bytes()), "Immediate folder aggregate", "", folder.files() + " files");
                for (String warning : scan.warnings()) row(out, "scan_note", scan.root().toString(), "", "", "", "", warning);
            }
            if (inventory != null) for (InstalledApp app : inventory.apps())
                row(out, "package", app.name(), Long.toString(app.bytes()), "", app.source(), app.version(), app.status() + "; " + app.description());
        }
    }
    static String cell(String value) {
        String safe = Models.safe(value);
        // Prevent spreadsheet formula execution from a crafted filename or metadata field.
        if (safe.matches("^[=+@\\-\\t].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static void row(Writer out, String... cells) throws IOException {
        out.write(String.join(",", Arrays.stream(cells).map(ReportWriter::cell).toList())); out.write('\n');
    }
}
