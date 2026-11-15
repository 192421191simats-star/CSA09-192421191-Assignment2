import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Q7 - Day-4 Design Challenge: diagnose, redesign and validate the Q5 live leaderboard.
 *
 * ORIGINAL  (Q5): ArrayList of teams; every top-10 query copies the list and sorts all n teams.
 * REDESIGN     : the ranking is KEPT in order (TreeSet with a total-order comparator) and a
 *                HashMap finds a team's current entry, so a score update touches O(log n) nodes
 *                and a top-k query reads only the first k entries. Nothing is re-sorted.
 *
 * Both designs receive exactly the same teams and the same sequence of updates (same seeds).
 *
 * Compile: javac -d out LeaderboardRedesign.java
 * Run    : java -Xms2g -Xmx2g -cp out LeaderboardRedesign input/experiment.txt
 */
public class LeaderboardRedesign {

    // ------------------------------------------------------------------ shared model (from Q5)
    /** Immutable: a changed score is a NEW Team object (see Q3 - never mutate a key inside a sorted/hashed set). */
    record Team(String name, int points, int goalDiff) { }

    /** Q5 order: points desc, goal difference desc, name asc. Names are unique, so this is a TOTAL order. */
    static final Comparator<Team> RANK = Comparator.comparingInt(Team::points).reversed()
            .thenComparing(Comparator.comparingInt(Team::goalDiff).reversed())
            .thenComparing(Team::name);

    /** Wraps a comparator and counts how many comparisons are made (operation count evidence). */
    static final class Counting<T> implements Comparator<T> {
        final Comparator<T> inner; long calls;
        Counting(Comparator<T> inner) { this.inner = inner; }
        public int compare(T a, T b) { calls++; return inner.compare(a, b); }
    }

    interface Board {
        void update(String name, int addPoints, int addGoalDiff);
        List<Team> top(int k);
    }

    // ------------------------------------------------------------------ ORIGINAL (Q5 design)
    static final class OriginalBoard implements Board {
        private final List<Team> items = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();   // only so updates find the team (not timed separately)
        private final Comparator<Team> order;
        long copyNs, sortNs;                                            // diagnosis: where the time goes

        OriginalBoard(List<Team> teams, Comparator<Team> order) {
            this.order = order;
            for (Team t : teams) { index.put(t.name(), items.size()); items.add(t); }
        }
        public void update(String name, int p, int g) {
            int i = index.get(name);
            Team old = items.get(i);
            items.set(i, new Team(name, old.points() + p, old.goalDiff() + g));
        }
        List<Team> ranking() {                                          // exactly the Q5 ranking()
            long a = System.nanoTime();
            List<Team> copy = new ArrayList<>(items);
            long b = System.nanoTime();
            copy.sort(order);
            long c = System.nanoTime();
            copyNs += b - a; sortNs += c - b;
            return copy;
        }
        public List<Team> top(int k) { return ranking().subList(0, Math.min(k, items.size())); }
    }

    // ------------------------------------------------------------------ REDESIGN
    /**
     * Generic, reusable: T = ranked item, K = its identity key.
     *   ordered  : TreeSet<T> kept in ranking order (red-black tree, O(log n) insert/remove)
     *   current  : HashMap<K, T> key -> the entry currently stored in the tree (O(1) find)
     */
    static final class IndexedLeaderboard<T, K> {
        private final TreeSet<T> ordered;
        private final Map<K, T> current = new HashMap<>();
        private final Function<? super T, ? extends K> key;

        IndexedLeaderboard(Comparator<? super T> totalOrder, Function<? super T, ? extends K> key) {
            this.ordered = new TreeSet<>(totalOrder);
            this.key = key;
        }
        /** Insert or replace. Requires compare(a,b)==0 only for the same item, otherwise TreeSet would drop it. */
        void put(T item) {
            T old = current.put(key.apply(item), item);
            if (old != null && !ordered.remove(old)) throw new IllegalStateException("tree out of sync for " + old);
            if (!ordered.add(item)) throw new IllegalStateException("comparator is not a total order: " + item);
        }
        T get(K k) { return current.get(k); }
        List<T> top(int k) {                                            // O(log n + k): walk the first k nodes
            List<T> r = new ArrayList<>(k);
            Iterator<T> it = ordered.iterator();
            while (r.size() < k && it.hasNext()) r.add(it.next());
            return r;
        }
        int size() { return ordered.size(); }
        Iterable<T> inOrder() { return ordered; }                       // full ranking, O(n), no sort
    }

    static final class RedesignedBoard implements Board {
        final IndexedLeaderboard<Team, String> lb;
        RedesignedBoard(List<Team> teams, Comparator<Team> order) {
            lb = new IndexedLeaderboard<>(order, Team::name);
            for (Team t : teams) lb.put(t);
        }
        public void update(String name, int p, int g) {
            Team old = lb.get(name);
            lb.put(new Team(name, old.points() + p, old.goalDiff() + g));   // remove old node, insert new node
        }
        public List<Team> top(int k) { return lb.top(k); }
    }

    // ------------------------------------------------------------------ measurement helpers
    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    @SuppressWarnings("deprecation")
    static long allocated() { return MX.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    static long usedAfterGc() { for (int i = 0; i < 3; i++) System.gc(); Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }

    static List<Team> teams(int n, long seed) {
        Random rnd = new Random(seed);
        List<Team> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(new Team(String.format("T%07d", i), 3 * rnd.nextInt(60), rnd.nextInt(41) - 20));
        Collections.shuffle(l, rnd);
        return l;
    }

    /** One match result: a random team gains 0, 1 or 3 points and a goal-difference change. */
    record Update(String name, int points, int goalDiff) { }
    static Update[] updates(List<Team> teams, int m, long seed) {
        Random rnd = new Random(seed);
        int[] pts = {0, 1, 3};
        Update[] u = new Update[m];
        for (int i = 0; i < m; i++) u[i] = new Update(teams.get(rnd.nextInt(teams.size())).name(), pts[rnd.nextInt(3)], rnd.nextInt(7) - 3);
        return u;
    }

    record Result(double msPerOp, double comparisonsPerOp, double bytesPerOp) { }

    static Result run(Board b, Update[] ups, Counting<Team> counter) {
        long sink = 0;
        long c0 = counter.calls, a0 = allocated(), t0 = System.nanoTime();
        for (Update u : ups) {
            b.update(u.name(), u.points(), u.goalDiff());
            sink += b.top(10).get(0).points();
        }
        long t1 = System.nanoTime(), a1 = allocated(), c1 = counter.calls;
        if (sink == 42) System.out.print("");
        return new Result((t1 - t0) / 1e6 / ups.length, (c1 - c0) / (double) ups.length, (a1 - a0) / (double) ups.length);
    }

    // ==================================================================================
    public static void main(String[] args) throws IOException {
        Map<String, String> cfg = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args.length > 0 ? args[0] : "input/experiment.txt")))
            if (!line.isBlank() && !line.startsWith("#")) { String[] p = line.split("=", 2); cfg.put(p[0].trim(), p[1].trim()); }
        int[] sizes = java.util.Arrays.stream(cfg.get("sizes").split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        long seed = Long.parseLong(cfg.get("seed"));
        int checkUpdates = Integer.parseInt(cfg.get("correctnessUpdates"));
        Files.createDirectories(Path.of("data"));

        // ------------------------------------------------ A. diagnosis of the original
        System.out.println("=== A. Diagnosis: where one ORIGINAL update + top-10 query spends its time ===");
        System.out.printf("  %-10s %10s %10s %10s %8s %16s %16s%n", "n", "total ms", "copy ms", "sort ms", "sort %",
                "comparisons", "bytes allocated");
        try (PrintWriter csv = new PrintWriter("data/q7_diagnosis.csv")) {
            csv.println("n,updates,total_ms,copy_ms,sort_ms,sort_share,comparisons_per_query,bytes_per_query");
            for (int n : sizes) {
                int m = n >= 1_000_000 ? 10 : n >= 100_000 ? 50 : 200;
                List<Team> ts = teams(n, seed);
                Counting<Team> cnt = new Counting<>(RANK);
                OriginalBoard ob = new OriginalBoard(ts, cnt);
                run(ob, updates(ts, 3, seed + 1), cnt);                          // warm-up
                ob.copyNs = 0; ob.sortNs = 0;
                Result r = run(ob, updates(ts, m, seed + 2), cnt);
                double copy = ob.copyNs / 1e6 / m, sort = ob.sortNs / 1e6 / m;
                System.out.printf("  %-10s %10.3f %10.3f %10.3f %7.1f%% %16s %16s%n", String.format("%,d", n), r.msPerOp(), copy, sort,
                        100 * sort / r.msPerOp(), String.format("%,.0f", r.comparisonsPerOp()), String.format("%,.0f", r.bytesPerOp()));
                csv.printf("%d,%d,%.4f,%.4f,%.4f,%.3f,%.0f,%.0f%n", n, m, r.msPerOp(), copy, sort, sort / r.msPerOp(), r.comparisonsPerOp(), r.bytesPerOp());
            }
        }
        System.out.println("  -> nearly all time is the full sort; comparisons grow as n log n although only ONE team changed");

        // ------------------------------------------------ correctness: identical results
        System.out.println("\n=== B. Correctness: redesigned top-10 must equal the original top-10 after EVERY update ===");
        for (int n : new int[]{1_000, 10_000}) {
            List<Team> ts = teams(n, seed);
            OriginalBoard ob = new OriginalBoard(ts, RANK);
            RedesignedBoard rb = new RedesignedBoard(ts, RANK);
            int mismatches = 0;
            for (Update u : updates(ts, checkUpdates, seed + 3)) {
                ob.update(u.name(), u.points(), u.goalDiff());
                rb.update(u.name(), u.points(), u.goalDiff());
                if (!ob.top(10).equals(rb.top(10))) mismatches++;
            }
            List<Team> full = new ArrayList<>();
            rb.lb.inOrder().forEach(full::add);
            boolean fullSame = full.equals(ob.ranking());
            System.out.printf("  n=%,d: %,d updates, top-10 mismatches %d, full final ranking identical: %b, size %,d%n",
                    n, checkUpdates, mismatches, fullSame, rb.lb.size());
        }
        // the total-order requirement, demonstrated
        IndexedLeaderboard<Team, String> noName = new IndexedLeaderboard<>(
                Comparator.comparingInt(Team::points).reversed().thenComparing(Comparator.comparingInt(Team::goalDiff).reversed()), Team::name);
        String outcome;
        try { noName.put(new Team("Ants", 9, 2)); noName.put(new Team("Bees", 9, 2)); outcome = "accepted (size " + noName.size() + ")"; }
        catch (IllegalStateException e) { outcome = "REJECTED: " + e.getMessage(); }
        System.out.println("  Comparator WITHOUT the name tie-break, two teams on (9,2): " + outcome);

        // ------------------------------------------------ E. original vs redesign, same conditions
        System.out.println("\n=== E. Original vs redesign: 1 update + top-10 query (same teams, same updates) ===");
        System.out.printf("  %-10s %-10s %14s %14s %14s %14s %14s%n", "n", "design", "ms / op", "comparisons", "bytes alloc",
                "build ms", "retained MB");
        try (PrintWriter raw = new PrintWriter("data/q7_raw.csv"); PrintWriter sum = new PrintWriter("data/q7_summary.csv")) {
            raw.println("n,design,run,updates,ms_per_op");
            sum.println("n,design,mean_ms_per_op,comparisons_per_op,bytes_per_op,build_ms,retained_MB,speedup");
            for (int n : sizes) {
                List<Team> ts = teams(n, seed);
                double origMs = 0;
                for (int d = 0; d < 2; d++) {
                    int m = d == 1 ? 20_000 : n >= 1_000_000 ? 10 : n >= 100_000 ? 50 : 200;
                    int runs = d == 1 ? 5 : n >= 1_000_000 ? 2 : 3;
                    // build + retained memory
                    long before = usedAfterGc();
                    long b0 = System.nanoTime();
                    Counting<Team> cnt = new Counting<>(RANK);
                    Board board = d == 0 ? new OriginalBoard(ts, cnt) : new RedesignedBoard(ts, cnt);
                    double buildMs = (System.nanoTime() - b0) / 1e6;
                    double retainedMB = (usedAfterGc() - before) / 1_048_576.0;
                    run(board, updates(ts, Math.min(m, 1000), seed + 9), cnt);  // warm-up
                    double total = 0; Result last = null;
                    for (int r = 1; r <= runs; r++) {
                        last = run(board, updates(ts, m, seed + 10 + r), cnt);
                        total += last.msPerOp();
                        raw.printf("%d,%s,%d,%d,%.6f%n", n, d == 0 ? "original" : "redesign", r, m, last.msPerOp());
                    }
                    double mean = total / runs;
                    if (d == 0) origMs = mean;
                    String speed = d == 0 ? "" : String.format("%.0fx faster", origMs / mean);
                    System.out.printf("  %-10s %-10s %14.5f %14s %14s %14.1f %14.1f   %s%n", String.format("%,d", n), d == 0 ? "original" : "redesign",
                            mean, String.format("%,.0f", last.comparisonsPerOp()), String.format("%,.0f", last.bytesPerOp()), buildMs, retainedMB, speed);
                    sum.printf("%d,%s,%.6f,%.1f,%.0f,%.2f,%.2f,%s%n", n, d == 0 ? "original" : "redesign", mean, last.comparisonsPerOp(),
                            last.bytesPerOp(), buildMs, retainedMB, d == 0 ? "" : String.format("%.1f", origMs / mean));
                    board = null;
                }
            }
        }
        System.out.println("  retained MB = heap kept by the board after GC (teams themselves included)");

        // ------------------------------------------------ F. where the original still wins
        System.out.println("\n=== F. Workload where the original can still be preferable: rare updates, full ranking printed ===");
        System.out.printf("  %-10s %22s %22s%n", "n", "original: 1 sort (ms)", "redesign: build (ms)");
        for (int n : new int[]{1_000, 100_000, 1_000_000}) {
            List<Team> ts = teams(n, seed);
            double o = 0, rdz = 0;
            for (int r = 0; r < 4; r++) {
                long t0 = System.nanoTime();
                List<Team> s = new ArrayList<>(ts); s.sort(RANK);
                long t1 = System.nanoTime();
                RedesignedBoard rb = new RedesignedBoard(ts, RANK);
                long t2 = System.nanoTime();
                if (r > 0) { o += (t1 - t0) / 1e6; rdz += (t2 - t1) / 1e6; }
                if (rb.lb.size() != s.size()) throw new IllegalStateException();
            }
            System.out.printf("  %-10s %22.2f %22.2f%n", String.format("%,d", n), o / 3, rdz / 3);
        }
        System.out.println("Raw data: data/q7_diagnosis.csv, data/q7_raw.csv, data/q7_summary.csv");
    }
}
