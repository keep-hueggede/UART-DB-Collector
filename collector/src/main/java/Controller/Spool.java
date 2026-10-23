package Controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Local buffer for UART lines that could not be stored (database unreachable).
 * One entry per line: "<receivedAt epoch ms>\t<raw JSON>". Survives restarts.
 */
public final class Spool {

    public record Entry(long receivedAt, String line) {}

    // ~30 MB; at one message per sensor and minute that is months of backlog
    static final int MAX_ENTRIES = 200_000;

    private final Path file;
    private int size;

    public Spool(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        Files.createDirectories(this.file.getParent());
        this.size = rawLines().size();
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized int size() {
        return size;
    }

    public Path getFile() {
        return file;
    }

    /** Appends an entry and syncs it to disk. False if the buffer is full. */
    public synchronized boolean append(long receivedAt, String line) {
        if (size >= MAX_ENTRIES) return false;
        String record = receivedAt + "\t" + line.replace('\n', ' ').replace('\r', ' ') + "\n";
        try {
            Files.writeString(file, record, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.DSYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        size++;
        return true;
    }

    /** All entries in arrival order; unreadable lines come back with receivedAt -1. */
    public synchronized List<Entry> readAll() {
        List<Entry> out = new ArrayList<>();
        for (String raw : rawLines()) {
            int tab = raw.indexOf('\t');
            long ts = -1;
            try {
                if (tab > 0) ts = Long.parseLong(raw.substring(0, tab));
            } catch (NumberFormatException ignored) {
                // keep -1
            }
            out.add(new Entry(ts, tab >= 0 ? raw.substring(tab + 1) : raw));
        }
        return out;
    }

    /** Removes the first n entries (atomically replaces the file). */
    public synchronized void removeFirst(int n) {
        if (n <= 0) return;
        List<String> lines = rawLines();
        List<String> rest = lines.subList(Math.min(n, lines.size()), lines.size());
        try {
            if (rest.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, rest, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        size = rest.size();
    }

    private List<String> rawLines() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            lines.removeIf(String::isBlank);
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
