import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Q1 - Railway e-ticket generation: String (+) vs StringBuilder vs StringBuffer
 * (plus String.join / StringJoiner / String.format as the alternative concept).
 *
 * Workloads are read from input/workloads.txt:  name  tickets  fieldsPerTicket  repetitions
 * Every repetition is written to data/q1_raw.csv; averages go to data/q1_summary.csv.
 *
 * Compile: javac -d out TicketBenchmark.java
 * Run    : java -Xms512m -Xmx512m -cp out TicketBenchmark input/workloads.txt
 */
public class TicketBenchmark {

    // ------------------------------------------------------------ ticket fields
    /** Builds realistic e-ticket fields once, so field creation is NOT part of the timing. */
    static String[] fields(int ticketNo, int n) {
        String[] f = new String[n];
        String[] fixed = {"PNR:" + (4_200_000_000L + ticketNo), "TRAIN:12631 NELLAI EXP",
                "FROM:MAS", "TO:TEN", "DATE:2026-11-20", "CLASS:3A", "COACH:B2", "QUOTA:GN"};
        for (int i = 0; i < n; i++) {
            if (i < fixed.length) f[i] = fixed[i];
            else {
                int p = i - fixed.length + 1;
                f[i] = switch (p % 4) {
                    case 0 -> "PAX" + p + ":PASSENGER NAME " + p;
                    case 1 -> "BERTH" + p + ":B2-" + (p % 72 + 1);
                    case 2 -> "AGE" + p + ":" + (18 + p % 60);
                    default -> "FARE" + p + ":Rs " + (845 + p % 300) + ".00";
                };
            }
        }
        return f;
    }

    // ------------------------------------------------------------ the approaches
    static String withConcat(String[] f) {
        String s = "";
        for (String x : f) s += x + "|";          // a NEW String object on every iteration
        return s;
    }

    static String withBuilder(String[] f) {
        StringBuilder sb = new StringBuilder();   // one growable buffer, not synchronised
        for (String x : f) sb.append(x).append('|');
        return sb.toString();
    }

    static String withBuffer(String[] f) {
        StringBuffer sb = new StringBuffer();     // same as StringBuilder, every call synchronised
        for (String x : f) sb.append(x).append('|');
        return sb.toString();
    }

    static String withJoin(String[] f) { return String.join("|", f) + "|"; }

    static String withJoiner(String[] f) {
        StringJoiner j = new StringJoiner("|", "", "|");
        for (String x : f) j.add(x);
        return j.toString();
    }

    static String withFormat(String[] f) {
        return String.format("%s|".repeat(f.length), (Object[]) f);   // parses the pattern every call
    }

    interface Approach { String build(String[] f); }
    record Named(String name, Approach a) { }

    static final List<Named> APPROACHES = List.of(
            new Named("String +", TicketBenchmark::withConcat),
            new Named("StringBuilder", TicketBenchmark::withBuilder),
            new Named("StringBuffer", TicketBenchmark::withBuffer),
            new Named("String.join", TicketBenchmark::withJoin),
            new Named("StringJoiner", TicketBenchmark::withJoiner),
            new Named("String.format", TicketBenchmark::withFormat));

    // ------------------------------------------------------------ measurement helpers
    static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @SuppressWarnings("deprecation")   // getId() keeps the code compatible with JDK 17
    static long allocated() { return MX.getThreadAllocatedBytes(Thread.currentThread().getId()); }

    static long heapUsed() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /** Estimated intermediate String objects for one ticket of n fields. */
    static long intermediateStrings(String approach, int n) {
        return switch (approach) {
            case "String +" -> n;          // one new (ever longer) String per += ; last one is the result
            case "String.format" -> 2;     // pattern from repeat() + result (plus n parsed FormatSpecifier objects)
            default -> 1;                  // only the final toString()/join result
        };
    }

    static long sink = 0;   // stops the JIT from discarding results

    public static void main(String[] args) throws IOException {
        Path wl = Path.of(args.length > 0 ? args[0] : "input/workloads.txt");
        Files.createDirectories(Path.of("data"));
        List<String[]> workloads = new ArrayList<>();
        for (String line : Files.readAllLines(wl)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            workloads.add(line.trim().split("\\s+"));
        }

        try (PrintWriter raw = new PrintWriter("data/q1_raw.csv");
             PrintWriter sum = new PrintWriter("data/q1_summary.csv")) {
            raw.println("workload,approach,tickets,fields,run,ms,allocated_bytes");
            sum.println("workload,approach,tickets,fields,mean_ms,stdev_ms,min_ms,alloc_MB_per_run,"
                    + "heap_before_MB,heap_after_MB,intermediate_strings,chars_per_ticket");

            for (String[] w : workloads) {
                String name = w[0];
                int tickets = Integer.parseInt(w[1]), n = Integer.parseInt(w[2]), reps = Integer.parseInt(w[3]);
                String[][] data = new String[tickets][];
                for (int t = 0; t < tickets; t++) data[t] = fields(t, n);
                int chars = withBuilder(data[0]).length();

                System.out.printf("%n=== Workload %s : %,d ticket(s) x %,d fields, %d repetitions (%,d chars/ticket) ===%n",
                        name, tickets, n, reps, chars);
                System.out.printf("%-14s %11s %10s %10s %14s %13s %14s%n", "approach", "mean ms", "stdev",
                        "min ms", "alloc MB/run", "heap MB b->a", "intermediates");

                String reference = null;
                for (Named ap : APPROACHES) {
                    // correctness: every approach must build exactly the same ticket
                    String one = ap.a().build(data[0]);
                    if (reference == null) reference = one;
                    else if (!reference.equals(one)) throw new IllegalStateException(ap.name() + " differs");

                    for (int warm = 0; warm < 2; warm++) runOnce(ap.a(), data);   // JIT warm-up
                    System.gc();
                    long heapBefore = heapUsed();
                    double[] ms = new double[reps];
                    long allocTotal = 0;
                    for (int r = 0; r < reps; r++) {
                        long a0 = allocated();
                        long t0 = System.nanoTime();
                        runOnce(ap.a(), data);
                        long t1 = System.nanoTime();
                        long a1 = allocated();
                        ms[r] = (t1 - t0) / 1e6;
                        allocTotal += a1 - a0;
                        raw.printf("%s,%s,%d,%d,%d,%.4f,%d%n", name, ap.name(), tickets, n, r + 1, ms[r], a1 - a0);
                    }
                    long heapAfter = heapUsed();
                    double mean = 0, min = Double.MAX_VALUE;
                    for (double m : ms) { mean += m; min = Math.min(min, m); }
                    mean /= reps;
                    double var = 0;
                    for (double m : ms) var += (m - mean) * (m - mean);
                    double sd = Math.sqrt(var / Math.max(1, reps - 1));
                    double allocMB = allocTotal / (double) reps / 1_048_576;
                    long inter = intermediateStrings(ap.name(), n) * tickets;
                    System.out.printf("%-14s %11.3f %10.3f %10.3f %14.2f %6.0f->%-6.0f %14s%n", ap.name(), mean, sd, min,
                            allocMB, heapBefore / 1_048_576.0, heapAfter / 1_048_576.0, String.format("%,d", inter));
                    sum.printf("%s,%s,%d,%d,%.4f,%.4f,%.4f,%.3f,%.1f,%.1f,%d,%d%n", name, ap.name(), tickets, n, mean, sd,
                            min, allocMB, heapBefore / 1_048_576.0, heapAfter / 1_048_576.0, inter, chars);
                }
            }
        }
        System.out.println("\nAll six approaches produced identical ticket text for every workload.");
        System.out.println("Raw data: data/q1_raw.csv   Summary: data/q1_summary.csv   (checksum " + sink + ")");
    }

    static void runOnce(Approach a, String[][] data) {
        for (String[] f : data) sink += a.build(f).length();
    }
}
