import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Q5 - A reusable, generic ranking component derived from the consistency principle,
 * tested with tie-heavy and adversarial data, faulty comparators and a contract checker.
 *
 * Compile: javac -d out RankingLab.java
 * Run    : java -Xms1g -Xmx1g -cp out RankingLab input/teams_ties.csv input/expected_ties.txt
 *                                                input/teams_adversarial.csv input/expected_adversarial.txt
 */
public class RankingLab {

    // ================================================================ design
    /** Anything that can be ranked: a primary score, a secondary score and a unique name. */
    interface Rankable {
        int primary();          // points, total marks, medals ...
        int secondary();        // goal difference, tie-break score ...
        String name();          // unique display name - the final tie-breaker
    }

    record Team(String name, int points, int goalDiff) implements Rankable {
        public int primary() { return points; }
        public int secondary() { return goalDiff; }
        @Override public String toString() { return name + "(" + points + "," + goalDiff + ")"; }
    }
    record Player(String name, int goals, int assists) implements Rankable {
        public int primary() { return goals; }
        public int secondary() { return assists; }
    }
    record College(String name, int gold, int silver) implements Rankable {
        public int primary() { return gold; }
        public int secondary() { return silver; }
    }
    /** A subtype of Team, used to show why addAll takes Collection<? extends T>. */
    record GuestTeam(Team team, String country) { }

    /** Ranking rules live in ONE place, as reusable Comparator objects. */
    static final class Orders {
        private Orders() { }

        /** Bounded type parameter: works for Team, Player, College ... any T extends Rankable. */
        static <T extends Rankable> Comparator<T> standard() {
            return Comparator.<T>comparingInt(Rankable::primary).reversed()      // higher first, no subtraction
                    .thenComparing(Comparator.<T>comparingInt(Rankable::secondary).reversed())
                    .thenComparing(Rankable::name);                                // total order: names are unique
        }
    }

    /** Generic leaderboard: T can be anything; the order is supplied (Comparator<? super T>). */
    static class Leaderboard<T> {
        private final List<T> items = new ArrayList<>();
        private final Comparator<? super T> order;

        Leaderboard(Comparator<? super T> order) { this.order = order; }

        void add(T item) { items.add(item); }

        /** PECS: a Leaderboard<Rankable> can accept a List<Team> ("producer extends"). */
        void addAll(Collection<? extends T> more) { items.addAll(more); }

        List<T> ranking() {                       // new sorted copy, items untouched
            List<T> copy = new ArrayList<>(items);
            copy.sort(order);
            return copy;
        }
        List<T> top(int k) { List<T> r = ranking(); return r.subList(0, Math.min(k, r.size())); }
        int size() { return items.size(); }
    }

    /** Bounded generic class: any Rankable gets the standard order without passing a Comparator. */
    static class RankedBoard<T extends Rankable> extends Leaderboard<T> {
        RankedBoard() { super(Orders.standard()); }
    }

    /** Wildcard in a utility: reads from a list of ANY Rankable subtype. */
    static double averagePrimary(List<? extends Rankable> list) {
        return list.stream().mapToInt(Rankable::primary).average().orElse(0);
    }

    // ================================================================ faulty comparators
    static final Map<String, Comparator<Team>> FAULTY = new LinkedHashMap<>();
    static {
        FAULTY.put("F1 subtraction (b.points - a.points)", (a, b) -> {
            int c = b.points() - a.points();                       // overflows for large |values|
            if (c != 0) return c;
            c = b.goalDiff() - a.goalDiff();
            return c != 0 ? c : a.name().compareTo(b.name());
        });
        FAULTY.put("F2 ignores the name tie-break", Comparator.comparingInt(Team::points).reversed()
                .thenComparing(Comparator.comparingInt(Team::goalDiff).reversed()));
        FAULTY.put("F3 never returns 0 (a>=b ? -1 : 1)", (a, b) -> a.points() >= b.points() ? -1 : 1);
        FAULTY.put("F4 .reversed() on the whole chain", Comparator.comparingInt(Team::points)
                .thenComparingInt(Team::goalDiff).thenComparing(Team::name).reversed());
    }

    // ================================================================ contract checker
    /** Checks the principle on every pair and triple: returns {antisymmetry, transitivity, equal-items} violations. */
    static <T> long[] checkContract(List<T> data, Comparator<? super T> c) {
        long anti = 0, trans = 0, eq = 0;
        int n = data.size();
        for (int i = 0; i < n; i++) {
            T a = data.get(i);
            if (c.compare(a, a) != 0) eq++;                                       // an item must equal itself
            for (int j = 0; j < n; j++) {
                T b = data.get(j);
                if (Integer.signum(c.compare(a, b)) != -Integer.signum(c.compare(b, a))) anti++;
                for (int k = 0; k < n; k++) {
                    T d = data.get(k);
                    if (c.compare(a, b) < 0 && c.compare(b, d) < 0 && c.compare(a, d) >= 0) trans++;
                }
            }
        }
        return new long[]{anti, trans, eq};
    }

    static List<Team> load(String file) throws IOException {
        List<Team> l = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split(",");
            l.add(new Team(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        }
        return l;
    }

    static List<String> expected(String file) throws IOException {
        for (String line : Files.readAllLines(Path.of(file)))
            if (!line.isBlank() && !line.startsWith("#")) return List.of(line.split(","));
        return List.of();
    }

    static List<String> names(List<Team> l) { return l.stream().map(Team::name).toList(); }

    static void evaluate(String title, List<Team> data, List<String> handRanking) {
        System.out.println("\n=== " + title + " (" + data.size() + " teams) ===");
        System.out.println("  hand-worked ranking : " + handRanking);
        Map<String, Comparator<Team>> all = new LinkedHashMap<>();
        all.put("CORRECT Orders.standard()", Orders.standard());
        all.putAll(FAULTY);
        System.out.printf("  %-40s %-8s %-9s %-10s %-10s %-11s %s%n", "comparator", "matches", "stable?*",
                "antisym.", "transitiv.", "self!=0", "result / note");
        for (Map.Entry<String, Comparator<Team>> e : all.entrySet()) {
            List<String> r1, r2;
            String note;
            try {
                List<Team> a = new ArrayList<>(data); a.sort(e.getValue()); r1 = names(a);
                List<Team> b = new ArrayList<>(data); Collections.reverse(b); b.sort(e.getValue()); r2 = names(b);
                note = r1.equals(handRanking) ? "" : String.join(",", r1);
            } catch (IllegalArgumentException ex) {
                r1 = List.of(); r2 = List.of(); note = "EXCEPTION: " + ex.getMessage();
            }
            long[] v = checkContract(data, e.getValue());
            System.out.printf("  %-40s %-8s %-9s %-10d %-10d %-11d %s%n", e.getKey(), r1.equals(handRanking) ? "YES" : "no",
                    r1.equals(r2) ? "same" : "DIFFERS", v[0], v[1], v[2], note);
        }
        System.out.println("  * stable? = same ranking when the input order is reversed (a total order must not depend on input order)");
    }

    public static void main(String[] args) throws IOException {
        String tiesFile = args.length > 0 ? args[0] : "input/teams_ties.csv";
        String tiesExp = args.length > 1 ? args[1] : "input/expected_ties.txt";
        String advFile = args.length > 2 ? args[2] : "input/teams_adversarial.csv";
        String advExp = args.length > 3 ? args[3] : "input/expected_adversarial.txt";
        Files.createDirectories(Path.of("data"));

        // ---------------------------------------------------- reuse of the design
        System.out.println("=== Reuse: one design, several rankable types ===");
        RankedBoard<Team> teams = new RankedBoard<>();
        teams.addAll(load(tiesFile));
        System.out.println("  Teams   : " + teams.top(5));
        RankedBoard<Player> players = new RankedBoard<>();
        players.addAll(List.of(new Player("Vijay", 7, 3), new Player("Anu", 7, 5), new Player("Bala", 9, 0), new Player("Arjun", 7, 5)));
        System.out.println("  Players : " + players.ranking());
        RankedBoard<College> colleges = new RankedBoard<>();
        colleges.addAll(List.of(new College("SIMATS", 12, 9), new College("PSG", 12, 11), new College("CEG", 10, 20)));
        System.out.println("  Colleges: " + colleges.ranking());
        Leaderboard<Rankable> mixed = new Leaderboard<>(Orders.standard());   // Comparator<Rankable> used for any subtype
        mixed.addAll(load(tiesFile).subList(0, 3));                           // List<Team> accepted by Collection<? extends Rankable>
        mixed.addAll(players.ranking().subList(0, 2));
        System.out.println("  Mixed Leaderboard<Rankable> (wildcards): " + mixed.ranking());
        System.out.printf("  averagePrimary(List<Team>) = %.2f, averagePrimary(List<College>) = %.2f%n",
                averagePrimary(teams.ranking()), averagePrimary(colleges.ranking()));

        // ---------------------------------------------------- correctness tests
        evaluate("DATA SET 1 - ties at every level", load(tiesFile), expected(tiesExp));
        evaluate("DATA SET 2 - adversarial (designed to break faulty comparators)", load(advFile), expected(advExp));

        // ---------------------------------------------------- F3 can crash TimSort on larger inputs
        System.out.println("\n=== F3 on larger random data (TimSort detects contract violations it happens to observe) ===");
        int crashes = 0, trials = 50;
        for (int t = 0; t < trials; t++) {
            List<Team> big = randomTeams(2_000, new Random(t), 4);
            try { big.sort(FAULTY.get("F3 never returns 0 (a>=b ? -1 : 1)")); }
            catch (IllegalArgumentException e) { crashes++; if (crashes == 1) System.out.println("  first exception: " + e.getMessage()); }
        }
        System.out.println("  IllegalArgumentException in " + crashes + " of " + trials + " sorts of 2,000 teams with 4 distinct point values");

        // ---------------------------------------------------- performance: ranking() cost and alternatives
        System.out.println("\n=== Cost of producing one ranking (ms, mean of 5 runs) ===");
        System.out.printf("  %-38s %12s %12s %12s%n", "approach", "n=10,000", "n=100,000", "n=1,000,000");
        String[] approaches = {"Comparator chain, 1 sort", "Comparable (natural order), 1 sort", "3 stable sorts (name, gd, points)"};
        try (PrintWriter csv = new PrintWriter("data/q5_sort.csv")) {
            csv.println("approach,n,run,ms");
            for (String ap : approaches) {
                System.out.printf("  %-38s", ap);
                for (int n : new int[]{10_000, 100_000, 1_000_000}) {
                    List<Team> base = randomTeams(n, new Random(n), 60);
                    List<NaturalTeam> nat = base.stream().map(NaturalTeam::new).toList();
                    List<String> reference = null;
                    double total = 0;
                    for (int r = 0; r < 7; r++) {
                        long t0 = System.nanoTime();
                        List<String> got;
                        switch (ap) {
                            case "Comparator chain, 1 sort" -> { List<Team> c = new ArrayList<>(base); c.sort(Orders.standard()); got = r == 0 ? names(c) : null; }
                            case "Comparable (natural order), 1 sort" -> { List<NaturalTeam> c = new ArrayList<>(nat); Collections.sort(c);
                                got = r == 0 ? c.stream().map(x -> x.t.name()).toList() : null; }
                            default -> {
                                List<Team> c = new ArrayList<>(base);
                                c.sort(Comparator.comparing(Team::name));                                   // least important first
                                c.sort(Comparator.comparingInt(Team::goalDiff).reversed());
                                c.sort(Comparator.comparingInt(Team::points).reversed());                   // most important last
                                got = r == 0 ? names(c) : null;
                            }
                        }
                        double ms = (System.nanoTime() - t0) / 1e6;
                        if (r == 0) {
                            List<Team> c = new ArrayList<>(base); c.sort(Orders.standard());
                            reference = names(c);
                            if (!got.equals(reference)) throw new IllegalStateException(ap + " gives a different ranking");
                        }
                        if (r >= 2) { total += ms; csv.printf("%s,%d,%d,%.3f%n", ap, n, r - 1, ms); }
                    }
                    System.out.printf(" %12.2f", total / 5);
                }
                System.out.println();
            }
        }
        System.out.println("  (all three approaches were checked to give the identical ranking)");

        // ---------------------------------------------------- live usage: update a score, then show the top 10
        System.out.println("\n=== Live leaderboard: 1 score update + top-10 query via ranking() (200 updates; 50 at 100k; 10 at 1M) ===");
        System.out.printf("  %-10s %16s %22s%n", "n", "ms per update", "objects sorted / query");
        try (PrintWriter csv = new PrintWriter("data/q5_live.csv")) {
            csv.println("n,updates,ms_per_update_and_top10");
            for (int n : new int[]{1_000, 10_000, 100_000, 1_000_000}) {
                int updates = n >= 1_000_000 ? 10 : n >= 100_000 ? 50 : 200;
                double ms = liveOriginal(n, updates, new Random(5));
                csv.printf("%d,%d,%.4f%n", n, updates, ms);
                System.out.printf("  %-10s %16.3f %22s%n", String.format("%,d", n), ms, String.format("%,d", n));
            }
        }
        System.out.println("Raw data: data/q5_sort.csv, data/q5_live.csv");
    }

    /** Original design in live use: every query copies and re-sorts the whole list. */
    static double liveOriginal(int n, int updates, Random rnd) {
        List<Team> base = randomTeams(n, rnd, 60);
        Leaderboard<Team> board = new Leaderboard<>(Orders.standard());
        board.addAll(base);
        List<Team> items = board.items;
        long sink = 0;
        for (int w = 0; w < 3; w++) sink += board.top(10).size();
        long t0 = System.nanoTime();
        for (int u = 0; u < updates; u++) {
            int i = rnd.nextInt(n);
            Team old = items.get(i);
            items.set(i, new Team(old.name(), old.points() + 3, old.goalDiff() + 1));   // a match result
            sink += board.top(10).get(0).points();
        }
        if (sink == 42) System.out.print("");
        return (System.nanoTime() - t0) / 1e6 / updates;
    }

    /** Alternative concept: Team implements Comparable (one fixed natural order). */
    static final class NaturalTeam implements Comparable<NaturalTeam> {
        final Team t;
        NaturalTeam(Team t) { this.t = t; }
        @Override public int compareTo(NaturalTeam o) {
            int c = Integer.compare(o.t.points(), t.points());
            if (c != 0) return c;
            c = Integer.compare(o.t.goalDiff(), t.goalDiff());
            return c != 0 ? c : t.name().compareTo(o.t.name());
        }
    }

    static List<Team> randomTeams(int n, Random rnd, int pointValues) {
        List<Team> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            l.add(new Team(String.format("T%07d", i), 3 * rnd.nextInt(pointValues), rnd.nextInt(41) - 20));
        Collections.shuffle(l, rnd);
        return l;
    }
}
