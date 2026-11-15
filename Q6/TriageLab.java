import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;

/**
 * Q6 - Emergency triage: own design with a PriorityQueue, a hand-verified check,
 * growth measurements, a starvation simulation with a fair (ageing) rule, and the
 * alternative designs (sorted LinkedList, array of five FIFO queues).
 *
 * Compile: javac -d out TriageLab.java
 * Run    : java -Xms1g -Xmx1g -cp out TriageLab input/patients_8.csv input/expected_8.txt
 */
public class TriageLab {

    /** A patient. seq = global arrival counter: unique, increasing, so ties are impossible. */
    record Patient(String name, int severity, long seq, int arrivalMinute) {
        @Override public String toString() { return name + "(S" + severity + ")"; }
    }

    /** The triage rule as ONE comparator: severity ascending, then arrival ascending. */
    static final Comparator<Patient> TRIAGE_ORDER =
            Comparator.comparingInt(Patient::severity).thenComparingLong(Patient::seq);

    /** Design 1 - binary heap. admit O(log n), next O(log n), peek O(1). */
    static final class HeapTriage {
        private final PriorityQueue<Patient> pq = new PriorityQueue<>(TRIAGE_ORDER);
        private long seq;
        Patient admit(String name, int sev, int minute) { Patient p = new Patient(name, sev, seq++, minute); pq.add(p); return p; }
        Patient next() { return pq.poll(); }
        int size() { return pq.size(); }
        /** Waiting list in treatment order: sorted COPY, the heap is not disturbed. O(n log n). */
        List<Patient> waitingList() { List<Patient> l = new ArrayList<>(pq); l.sort(TRIAGE_ORDER); return l; }
        String rawHeap() { return pq.toString(); }
    }

    /** Alternative A - LinkedList kept sorted. admit O(n) (walk to position), next O(1). */
    static final class SortedListTriage {
        private final LinkedList<Patient> list = new LinkedList<>();
        private long seq;
        void admit(String name, int sev, int minute) {
            Patient p = new Patient(name, sev, seq++, minute);
            ListIterator<Patient> it = list.listIterator(list.size());
            while (it.hasPrevious()) {                                 // walk backwards: new arrival goes AFTER equals
                if (TRIAGE_ORDER.compare(it.previous(), p) <= 0) { it.next(); break; }
            }
            it.add(p);
        }
        Patient next() { return list.pollFirst(); }
        /** Pre-fill for experiments only: sort once instead of n ordered inserts. */
        void bulkLoad(List<Patient> ps) { List<Patient> c = new ArrayList<>(ps); c.sort(TRIAGE_ORDER); list.addAll(c); seq = ps.size(); }
    }

    /** Alternative B / fair design - one FIFO queue per severity. admit O(1), next O(levels) = O(1). */
    static final class LevelQueuesTriage {
        @SuppressWarnings("unchecked")
        private final Deque<Patient>[] q = new Deque[6];
        private long seq;
        private final int ageingStep;            // minutes of waiting that raise priority by one level; 0 = no ageing
        private final int highestAgedLevel;      // an aged patient can climb to this level but not above it
        LevelQueuesTriage(int ageingStep) { this(ageingStep, 2); }
        LevelQueuesTriage(int ageingStep, int highestAgedLevel) {
            this.ageingStep = ageingStep; this.highestAgedLevel = highestAgedLevel;
            for (int i = 1; i <= 5; i++) q[i] = new ArrayDeque<>();
        }
        void admit(String name, int sev, int minute) { q[sev].addLast(new Patient(name, sev, seq++, minute)); }

        /** Effective level of a waiting patient: one level per ageingStep minutes waited, never above highestAgedLevel. */
        int effective(Patient p, int now) {
            if (ageingStep == 0 || p.severity() <= highestAgedLevel) return p.severity();
            return Math.max(highestAgedLevel, p.severity() - (now - p.arrivalMinute()) / ageingStep);
        }

        /** Only the HEAD of each level can be the next patient (FIFO inside a level), so 5 candidates. */
        Patient next(int now) {
            Patient best = null; int bestLevel = 0; int from = 0;
            for (int s = 1; s <= 5; s++) {
                Patient h = q[s].peekFirst();
                if (h == null) continue;
                int e = effective(h, now);
                if (best == null || e < bestLevel || (e == bestLevel && h.seq() < best.seq())) { best = h; bestLevel = e; from = s; }
            }
            return best == null ? null : q[from].pollFirst();
        }
        int size() { int n = 0; for (int s = 1; s <= 5; s++) n += q[s].size(); return n; }
    }

    // ===================================================================================
    public static void main(String[] args) throws IOException {
        Files.createDirectories(Path.of("data"));
        String file = args.length > 0 ? args[0] : "input/patients_8.csv";
        String expFile = args.length > 1 ? args[1] : "input/expected_8.txt";

        // ---------------------------------------------- 1. correctness check (hand-verified)
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(file)))
            if (!line.isBlank() && !line.startsWith("#")) rows.add(line.split(","));
        List<String> expected = Files.readAllLines(Path.of(expFile)).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).findFirst().map(l -> List.of(l.split(","))).orElseThrow();

        System.out.println("=== 1. Hand-verified instance (" + rows.size() + " patients) ===");
        HeapTriage er = new HeapTriage();
        Queue<Patient> fifo = new ArrayDeque<>();
        long s = 0;
        for (String[] r : rows) {
            int minute = LocalTime.parse(r[0]).toSecondOfDay() / 60;
            Patient p = er.admit(r[1], Integer.parseInt(r[2]), minute);
            fifo.add(new Patient(r[1], Integer.parseInt(r[2]), s++, minute));
            System.out.printf("  %s admit %-7s severity %s   arrival seq %d%n", r[0], r[1], r[2], p.seq());
        }
        System.out.println("  Printing the PriorityQueue directly : " + er.rawHeap());
        System.out.println("  Waiting list (sorted copy)          : " + er.waitingList());
        System.out.println("  Plain FIFO queue (ArrayDeque) order : " + fifo + "   <- ignores severity");
        List<String> served = new ArrayList<>();
        Patient p;
        while ((p = er.next()) != null) served.add(p.name());
        System.out.println("  Program treatment order : " + served);
        System.out.println("  Hand-worked order       : " + expected);
        System.out.println("  MATCH: " + served.equals(expected));

        // ---------------------------------------------- 2. growth
        System.out.println("\n=== 2. Growth: ns per admit / per next-patient at queue size n (mean of 5 runs) ===");
        System.out.printf("  %-28s %12s %12s %12s %12s%n", "design / operation", "n=1,000", "n=10,000", "n=100,000", "n=1,000,000");
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        String[] rowsOut = {"PriorityQueue admit", "PriorityQueue next", "Sorted LinkedList admit", "Sorted LinkedList next",
                "5 FIFO queues admit", "5 FIFO queues next"};
        double[][] res = new double[rowsOut.length][sizes.length];
        for (int w = 0; w < 15; w++) for (int d = 0; d < 3; d++) growth(d, 10_000, new Random(w));   // JIT warm-up
        try (PrintWriter csv = new PrintWriter("data/q6_growth.csv")) {
            csv.println("design,operation,n,run,ns_per_op");
            for (int si = 0; si < sizes.length; si++) {
                int n = sizes[si];
                for (int design = 0; design < 3; design++) {
                    if (design == 1 && n > 100_000) { res[2][si] = Double.NaN; res[3][si] = Double.NaN; continue; }  // too slow: skipped
                    double admit = 0, next = 0;
                    for (int run = 0; run < 7; run++) {
                        double[] v = growth(design, n, new Random(run));
                        if (run >= 2) {
                            admit += v[0]; next += v[1];
                            String d = design == 0 ? "PriorityQueue" : design == 1 ? "SortedLinkedList" : "FiveFifoQueues";
                            csv.printf("%s,admit,%d,%d,%.2f%n%s,next,%d,%d,%.2f%n", d, n, run - 1, v[0], d, n, run - 1, v[1]);
                        }
                    }
                    res[design * 2][si] = admit / 5; res[design * 2 + 1][si] = next / 5;
                }
            }
        }
        for (int r = 0; r < rowsOut.length; r++) {
            System.out.printf("  %-28s", rowsOut[r]);
            for (double v : res[r]) System.out.printf(" %12s", Double.isNaN(v) ? "skipped*" : String.format("%.1f", v));
            System.out.println();
        }
        System.out.println("  (each measurement: queue pre-filled to n, then 10,000 admits and 10,000 next() at that size;");
        System.out.println("   sorted LinkedList at n=100,000: 1,000 of each)");
        System.out.println("  * sorted LinkedList at 1,000,000: each admit walks ~10^5-10^6 nodes; not run");

        // ---------------------------------------------- 3. starvation and the fair rule
        int shifts = 30;
        System.out.println("\n=== 3. Starvation simulation: " + shifts + " independent 12-hour shifts, 1 doctor, 6 min per patient ===");
        System.out.println("  arrivals/hour: S1 1.5, S2 3, S3 6 (11.5/h incl. S4,S5 vs capacity 10/h) for 6 h, then S1-S3 x0.75; S4 0.5, S5 0.5");
        try (PrintWriter csv = new PrintWriter("data/q6_starvation.csv")) {
            csv.println("design,severity,arrived,served,still_waiting,mean_wait_min,max_wait_min");
            for (int design = 0; design < 4; design++) {
                String name = switch (design) { case 0 -> "PriorityQueue (strict)"; case 1 -> "5 FIFO queues, no ageing";
                    case 2 -> "5 FIFO queues + ageing 30 min/level, may climb to level 2";
                    default -> "5 FIFO queues + ageing 30 min/level, may climb to level 3"; };
                long[][] stats = new long[6][5];
                for (int sh = 0; sh < shifts; sh++) {                     // same 30 arrival streams for every design
                    long[][] one = simulate(design, new Random(1000 + sh));
                    for (int sev = 1; sev <= 5; sev++) {
                        for (int k = 0; k < 4; k++) stats[sev][k] += one[sev][k];
                        stats[sev][4] = Math.max(stats[sev][4], one[sev][4]);
                    }
                }
                System.out.println("  " + name);
                System.out.printf("    %-9s %8s %8s %14s %14s %14s%n", "severity", "arrived", "served", "still waiting", "mean wait min", "max wait min");
                for (int sev = 1; sev <= 5; sev++) {
                    long arrived = stats[sev][0], servedN = stats[sev][1], waiting = stats[sev][2], sumW = stats[sev][3], maxW = stats[sev][4];
                    double mean = servedN == 0 ? 0 : sumW / (double) servedN;
                    System.out.printf("    S%-8d %8d %8d %14d %14.1f %14d%n", sev, arrived, servedN, waiting, mean, maxW);
                    csv.printf("%s,%d,%d,%d,%d,%.1f,%d%n", name, sev, arrived, servedN, waiting, mean, maxW);
                }
            }
        }
        System.out.println("  totals over all shifts; max wait includes patients still waiting at shift end (wait so far)");

        // ---------------------------------------------- 4. deployment: 5,000 patients/day
        System.out.println("\n=== 4. City hospital: 5,000 patients/day through one PriorityQueue ===");
        HeapTriage city = new HeapTriage();
        Random rnd = new Random(5);
        long t0 = System.nanoTime();
        int maxSize = 0;
        for (int i = 0; i < 5_000; i++) {
            city.admit("P" + i, 1 + rnd.nextInt(5), i * 1440 / 5_000);
            maxSize = Math.max(maxSize, city.size());
            if (i % 3 != 2) city.next();                       // doctors fall behind: backlog grows to ~1/3 of arrivals
        }
        long t1 = System.nanoTime();
        List<Patient> board = city.waitingList();
        long t2 = System.nanoTime();
        System.out.printf("  5,000 admits + %,d treatments: %.2f ms total (%.2f us per patient); peak queue %,d%n",
                5_000 - city.size(), (t1 - t0) / 1e6, (t1 - t0) / 1e3 / 5_000, maxSize);
        System.out.printf("  Rebuild waiting-list display of %,d patients: %.3f ms%n", board.size(), (t2 - t1) / 1e6);
    }

    /** Fill to n, then time 10,000 admits and 10,000 next() calls at that size. Returns {ns/admit, ns/next}. */
    static double[] growth(int design, int n, Random rnd) {
        int ops = design == 1 && n >= 100_000 ? 1_000 : 10_000;   // sorted list is O(n) per admit
        HeapTriage h = null; SortedListTriage l = null; LevelQueuesTriage q = null;
        switch (design) {
            case 0 -> { h = new HeapTriage(); for (int i = 0; i < n; i++) h.admit("p", 1 + rnd.nextInt(5), 0); }
            case 1 -> { l = new SortedListTriage(); List<Patient> ps = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) ps.add(new Patient("p", 1 + rnd.nextInt(5), i, 0));
                        l.bulkLoad(ps); }
            default -> { q = new LevelQueuesTriage(0); for (int i = 0; i < n; i++) q.admit("p", 1 + rnd.nextInt(5), 0); }
        }
        long sink = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            int sev = 1 + rnd.nextInt(5);
            if (h != null) h.admit("p", sev, 0); else if (l != null) l.admit("p", sev, 0); else q.admit("p", sev, 0);
        }
        long t1 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            Patient p = h != null ? h.next() : l != null ? l.next() : q.next(0);
            sink += p.seq();
        }
        long t2 = System.nanoTime();
        if (sink == 42) System.out.print("");
        return new double[]{(t1 - t0) / (double) ops, (t2 - t1) / (double) ops};
    }

    /** Minute-by-minute simulation. stats[sev] = {arrived, served, stillWaiting, sumWait, maxWait}. */
    static long[][] simulate(int design, Random rnd) {
        double[] perHour = {0, 1.5, 3, 6, 0.5, 0.5};
        long[][] st = new long[6][5];
        HeapTriage heap = new HeapTriage();
        LevelQueuesTriage levels = new LevelQueuesTriage(design >= 2 ? 30 : 0, design == 3 ? 3 : 2);
        int shift = 12 * 60, service = 6, busyUntil = 0;
        for (int t = 0; t < shift; t++) {
            for (int sev = 1; sev <= 5; sev++) {
                double rate = perHour[sev] * (sev <= 3 && t >= 6 * 60 ? 0.75 : 1.0) / 60.0;
                if (rnd.nextDouble() < rate) {
                    st[sev][0]++;
                    if (design == 0) heap.admit("p", sev, t); else levels.admit("p", sev, t);
                }
            }
            if (t >= busyUntil) {
                Patient p = design == 0 ? heap.next() : levels.next(t);
                if (p != null) {
                    long w = t - p.arrivalMinute();
                    st[p.severity()][1]++; st[p.severity()][3] += w; st[p.severity()][4] = Math.max(st[p.severity()][4], w);
                    busyUntil = t + service;
                }
            }
        }
        List<Patient> left = new ArrayList<>();
        if (design == 0) { Patient p; while ((p = heap.next()) != null) left.add(p); }
        else { Patient p; while ((p = levels.next(shift)) != null) left.add(p); }
        for (Patient p : left) {
            st[p.severity()][2]++;
            st[p.severity()][4] = Math.max(st[p.severity()][4], shift - p.arrivalMinute());
        }
        return st;
    }
}
