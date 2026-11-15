import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Q1 Design Decision - several booking threads append to ONE shared booking log.
 * Each thread builds its own ticket text (per-ticket text) and appends a log line.
 *
 * Designs compared:
 *   A. shared StringBuilder   (not thread-safe)
 *   B. shared StringBuffer    (every append synchronised)
 *   C. per-thread StringBuilder for the ticket + one synchronized append of the
 *      finished line to a shared StringBuilder (lock held once per line)
 *   D. per-thread StringBuilder + ConcurrentLinkedQueue<String> of finished lines
 *
 * Compile: javac -d out SharedLogDemo.java
 * Run    : java -cp out SharedLogDemo 4 50000 5     (threads, bookingsPerThread, trials)
 */
public class SharedLogDemo {

    /** A well-formed log line; interleaved appends from two threads break this pattern. */
    static final java.util.regex.Pattern LINE =
            java.util.regex.Pattern.compile("T\\d+ PNR:\\d{10} B2-\\d{1,2} Rs 845\\.00");

    static String ticketLine(int thread, int i) {
        StringBuilder sb = new StringBuilder(64);        // per-ticket text: local, so StringBuilder is safe
        return sb.append("T").append(thread).append(" PNR:").append(4_200_000_000L + i)
                 .append(" B2-").append(i % 72 + 1).append(" Rs 845.00\n").toString();
    }

    interface Design { void run(int threads, int perThread) throws Exception; long length(); void reset(); }

    static long expectedLength(int threads, int perThread) {
        long total = 0;
        for (int t = 0; t < threads; t++)
            for (int i = 0; i < perThread; i++) total += ticketLine(t, i).length();
        return total;
    }

    static void startAll(int threads, java.util.function.IntConsumer body) throws InterruptedException {
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts.add(new Thread(() -> body.accept(id)));
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
    }

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int per = args.length > 1 ? Integer.parseInt(args[1]) : 50_000;
        int trials = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        long expected = expectedLength(threads, per);
        Files.createDirectories(Path.of("data"));

        System.out.printf("%d threads x %,d bookings each, expected log length %,d chars, %d trials%n%n",
                threads, per, expected, trials);
        System.out.printf("%-38s %-6s %-10s %-14s %-10s %s%n", "design", "trial", "time ms", "log length", "bad lines", "result");

        String[] names = {"A shared StringBuilder", "B shared StringBuffer",
                "C local SB + synchronized line append", "D local SB + ConcurrentLinkedQueue"};
        try (PrintWriter csv = new PrintWriter("data/q1_shared_log.csv")) {
            csv.println("design,trial,ms,length,expected,broken_lines,correct,exception");
            for (int d = 0; d < 4; d++) {
                int wrong = 0;
                double totalMs = 0;
                for (int trial = 1; trial <= trials; trial++) {
                    final StringBuilder shared = new StringBuilder();
                    final StringBuffer buffer = new StringBuffer();
                    final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
                    final String[] error = {""};
                    final int design = d;
                    long t0 = System.nanoTime();
                    startAll(threads, id -> {
                        try {
                            for (int i = 0; i < per; i++) {
                                switch (design) {
                                    case 0 -> {                               // unsafe: many small appends
                                        shared.append("T").append(id).append(" PNR:").append(4_200_000_000L + i)
                                              .append(" B2-").append(i % 72 + 1).append(" Rs 845.00\n");
                                    }
                                    case 1 -> {
                                        buffer.append("T").append(id).append(" PNR:").append(4_200_000_000L + i)
                                              .append(" B2-").append(i % 72 + 1).append(" Rs 845.00\n");
                                    }
                                    case 2 -> {
                                        String line = ticketLine(id, i);
                                        synchronized (shared) { shared.append(line); }
                                    }
                                    default -> queue.add(ticketLine(id, i));
                                }
                            }
                        } catch (RuntimeException e) {
                            synchronized (error) { error[0] = e.getClass().getSimpleName(); }
                        }
                    });
                    double ms = (System.nanoTime() - t0) / 1e6;
                    long len = switch (d) {
                        case 0, 2 -> shared.length();
                        case 1 -> buffer.length();
                        default -> queue.stream().mapToLong(String::length).sum();
                    };
                    String text = switch (d) {
                        case 0, 2 -> shared.toString();
                        case 1 -> buffer.toString();
                        default -> String.join("", queue);
                    };
                    long broken = text.lines().filter(l -> !LINE.matcher(l).matches()).count();
                    boolean ok = len == expected && error[0].isEmpty() && broken == 0;
                    if (!ok) wrong++;
                    totalMs += ms;
                    System.out.printf("%-38s %-6d %-10.1f %-14s %-10s %s%n", names[d], trial, ms, String.format("%,d", len),
                            String.format("%,d", broken),
                            ok ? "correct" : "CORRUPT (" + (expected - len) + " chars lost"
                                    + (error[0].isEmpty() ? "" : ", " + error[0]) + ")");
                    csv.printf("%s,%d,%.3f,%d,%d,%d,%b,%s%n", names[d], trial, ms, len, expected, broken, ok, error[0]);
                }
                System.out.printf("%-38s mean %.1f ms, corrupted in %d of %d trials%n%n", names[d], totalMs / trials, wrong, trials);
            }
        }
        System.out.println("Raw data: data/q1_shared_log.csv");
    }
}
