import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Q4 - Hospital pharmacy stock lookup: HashMap vs TreeMap vs LinkedHashMap
 * (+ Hashtable, + sorted array with Arrays.binarySearch as the alternative concept).
 *
 * Part 1 uses input/medicines_sample.csv to show both evening reports and check their order.
 * Part 2 measures put / 100,000 lookups / both reports at several inventory sizes.
 *
 * Compile: javac -d out PharmacyInventory.java
 * Run    : java -Xms1g -Xmx1g -cp out PharmacyInventory input/medicines_sample.csv
 */
public class PharmacyInventory {

    record Medicine(String code, String name, int stock) { }

    static final int LOOKUPS = 100_000;
    static final int REPS = 5;
    static long sink;

    // ------------------------------------------------------------ inventory abstraction
    /**
     * One inventory design = a map + whatever EXTRA work it needs to produce the reports.
     *  - code-order report      : TreeMap natural; others must sort the keys
     *  - arrival-order report   : LinkedHashMap natural; others must keep a separate arrival list
     */
    static final class Inventory {
        final String kind;
        final Map<String, Medicine> map;
        final List<String> arrival;          // only for maps that forget insertion order

        Inventory(String kind) {
            this.kind = kind;
            map = switch (kind) {
                case "HashMap" -> new HashMap<>();
                case "TreeMap" -> new TreeMap<>();
                case "LinkedHashMap" -> new LinkedHashMap<>();
                case "Hashtable" -> new Hashtable<>();
                default -> throw new IllegalArgumentException(kind);
            };
            arrival = kind.equals("LinkedHashMap") ? null : new ArrayList<>();
        }

        void add(Medicine m) {
            if (map.put(m.code(), m) == null && arrival != null) arrival.add(m.code());
        }

        Medicine get(String code) { return map.get(code); }

        /** Evening report 1: all medicines in code order. */
        String codeOrderReport() {
            StringBuilder sb = new StringBuilder(map.size() * 40);
            if (map instanceof TreeMap) {
                for (Medicine m : map.values()) line(sb, m);                  // already sorted
            } else {
                String[] keys = map.keySet().toArray(new String[0]);         // EXTRA: copy + sort
                Arrays.sort(keys);
                for (String k : keys) line(sb, map.get(k));
            }
            return sb.toString();
        }

        /** Evening report 2: medicines in the order they were added. */
        String arrivalOrderReport() {
            StringBuilder sb = new StringBuilder(map.size() * 40);
            if (arrival == null) for (Medicine m : map.values()) line(sb, m);  // LinkedHashMap
            else for (String k : arrival) line(sb, map.get(k));               // EXTRA: list + lookups
            return sb.toString();
        }

        static void line(StringBuilder sb, Medicine m) {
            sb.append(m.code()).append(' ').append(m.name()).append(' ').append(m.stock()).append('\n');
        }
    }

    // ------------------------------------------------------------ alternative: sorted array
    static final class SortedArrayInventory {
        String[] codes; Medicine[] meds; int size;

        SortedArrayInventory(List<Medicine> all) {                         // O(n log n) preprocessing
            Medicine[] a = all.toArray(new Medicine[0]);
            Arrays.sort(a, (x, y) -> x.code().compareTo(y.code()));
            meds = Arrays.copyOf(a, a.length + 1024);
            codes = new String[meds.length];
            for (int i = 0; i < a.length; i++) codes[i] = a[i].code();
            size = a.length;
        }
        Medicine get(String code) {                                         // O(log n)
            int i = Arrays.binarySearch(codes, 0, size, code);
            return i >= 0 ? meds[i] : null;
        }
        void insert(Medicine m) {                                           // O(log n) search + O(n) shift
            int i = Arrays.binarySearch(codes, 0, size, m.code());
            if (i >= 0) { meds[i] = m; return; }
            i = -i - 1;
            if (size == codes.length) { codes = Arrays.copyOf(codes, size * 2); meds = Arrays.copyOf(meds, size * 2); }
            System.arraycopy(codes, i, codes, i + 1, size - i);
            System.arraycopy(meds, i, meds, i + 1, size - i);
            codes[i] = m.code(); meds[i] = m; size++;
        }
        String codeOrderReport() {                                          // O(n), already sorted
            StringBuilder sb = new StringBuilder(size * 40);
            for (int i = 0; i < size; i++) Inventory.line(sb, meds[i]);
            return sb.toString();
        }
    }

    // ------------------------------------------------------------ data generation
    static List<Medicine> generate(int n, long seed) {
        Random rnd = new Random(seed);
        java.util.Set<String> used = new java.util.HashSet<>();
        List<Medicine> list = new ArrayList<>(n);
        while (list.size() < n) {
            String code = String.format("MED%07d", rnd.nextInt(10_000_000));
            if (used.add(code)) list.add(new Medicine(code, "Drug-" + list.size(), rnd.nextInt(1000)));
        }
        return list;                                                        // arrival order = random code order
    }

    static String[] lookupKeys(List<Medicine> meds, Random rnd) {
        String[] keys = new String[LOOKUPS];
        for (int i = 0; i < LOOKUPS; i++)
            keys[i] = rnd.nextInt(10) == 0 ? "MEDX" + i                       // 10% misses (unknown code)
                    : meds.get(rnd.nextInt(meds.size())).code();
        return keys;
    }

    static List<String> codesOf(String report) {
        List<String> c = new ArrayList<>();
        report.lines().forEach(l -> c.add(l.substring(0, l.indexOf(' '))));
        return c;
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Path.of("data"));
        // ================= Part 1: correctness of both reports on the sample file =================
        List<Medicine> sample = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args.length > 0 ? args[0] : "input/medicines_sample.csv"))) {
            if (line.isBlank() || line.startsWith("code")) continue;
            String[] p = line.split(",");
            sample.add(new Medicine(p[0], p[1], Integer.parseInt(p[2])));
        }
        List<String> arrivalTruth = sample.stream().map(Medicine::code).toList();
        List<String> codeTruth = arrivalTruth.stream().sorted().toList();

        System.out.println("=== Part 1: sample inventory (" + sample.size() + " medicines) ===");
        Inventory lhm = new Inventory("LinkedHashMap");
        Inventory tm = new Inventory("TreeMap");
        sample.forEach(lhm::add);
        sample.forEach(tm::add);
        System.out.println("Lookup MED" + sample.get(3).code().substring(3) + " -> " + lhm.get(sample.get(3).code()));
        System.out.println("Lookup MED000000 -> " + lhm.get("MED000000") + "   (unknown code)");
        System.out.println("\nReport 1 - code order (TreeMap, natural):");
        System.out.print(indent(tm.codeOrderReport()));
        System.out.println("\nReport 2 - order added (LinkedHashMap, natural):");
        System.out.print(indent(lhm.arrivalOrderReport()));

        System.out.println("\nOrder check for each map WITHOUT extra work (plain values() iteration):");
        System.out.printf("  %-14s %-22s %-22s%n", "map", "matches code order?", "matches arrival order?");
        for (String k : new String[]{"HashMap", "TreeMap", "LinkedHashMap"}) {
            Map<String, Medicine> m = k.equals("HashMap") ? new HashMap<>() : k.equals("TreeMap") ? new TreeMap<>() : new LinkedHashMap<>();
            sample.forEach(x -> m.put(x.code(), x));
            List<String> it = new ArrayList<>(m.keySet());
            System.out.printf("  %-14s %-22s %-22s%n", k, it.equals(codeTruth), it.equals(arrivalTruth));
        }

        // ================= Part 2: measurements at several sizes =================
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        String[] kinds = {"HashMap", "TreeMap", "LinkedHashMap", "Hashtable"};
        System.out.println("\n=== Part 2: timings (mean of " + REPS + " runs after warm-up) ===");
        System.out.printf("%-14s %-10s %10s %14s %12s %12s %12s %12s %10s %10s%n", "map", "n", "put ns/op",
                "100k get ms", "ns/lookup", "p99 ns", "code rpt ms", "arr rpt ms", "code ok", "arr ok");

        // global JIT warm-up so the first (smallest) size is not measured on cold code
        List<Medicine> warm = generate(20_000, 1);
        String[] warmKeys = lookupKeys(warm, new Random(1));
        for (int w = 0; w < 5; w++)
            for (String kind : kinds) {
                Inventory inv = new Inventory(kind);
                warm.forEach(inv::add);
                for (String k : warmKeys) if (inv.get(k) != null) sink++;
                sink += inv.codeOrderReport().length() + inv.arrivalOrderReport().length();
            }

        try (PrintWriter raw = new PrintWriter("data/q4_raw.csv");
             PrintWriter sum = new PrintWriter("data/q4_summary.csv")) {
            raw.println("structure,n,run,put_ns_per_op,lookups_100k_ms,code_report_ms,arrival_report_ms");
            sum.println("structure,n,put_ns_per_op,lookups_100k_ms,ns_per_lookup,p99_lookup_ns,code_report_ms,arrival_report_ms,code_order_ok,arrival_order_ok");
            for (int n : sizes) {
                List<Medicine> meds = generate(n, n);
                List<String> arr = meds.stream().map(Medicine::code).toList();
                List<String> sorted = arr.stream().sorted().toList();
                String[] keys = lookupKeys(meds, new Random(7));
                for (String kind : kinds) {
                    double put = 0, get = 0, rc = 0, ra = 0;
                    boolean codeOk = false, arrOk = false;
                    long p99 = 0;
                    for (int r = 0; r < REPS + 2; r++) {                          // first 2 = warm-up
                        Inventory inv = new Inventory(kind);
                        long t0 = System.nanoTime();
                        for (Medicine m : meds) inv.add(m);
                        long t1 = System.nanoTime();
                        for (String k : keys) { Medicine m = inv.get(k); if (m != null) sink += m.stock(); }
                        long t2 = System.nanoTime();
                        String codeRep = inv.codeOrderReport();
                        long t3 = System.nanoTime();
                        String arrRep = inv.arrivalOrderReport();
                        long t4 = System.nanoTime();
                        if (r >= 2) {
                            double pu = (t1 - t0) / (double) n, ge = (t2 - t1) / 1e6, c = (t3 - t2) / 1e6, a = (t4 - t3) / 1e6;
                            put += pu; get += ge; rc += c; ra += a;
                            raw.printf("%s,%d,%d,%.2f,%.3f,%.3f,%.3f%n", kind, n, r - 1, pu, ge, c, a);
                        }
                        if (r == REPS + 1) {
                            codeOk = codesOf(codeRep).equals(sorted);
                            arrOk = codesOf(arrRep).equals(arr);
                            p99 = p99Lookup(inv, keys);
                        }
                    }
                    put /= REPS; get /= REPS; rc /= REPS; ra /= REPS;
                    System.out.printf("%-14s %-10s %10.1f %14.3f %12.1f %12d %12.3f %12.3f %10s %10s%n", kind,
                            String.format("%,d", n), put, get, get * 1e6 / LOOKUPS, p99, rc, ra, codeOk, arrOk);
                    sum.printf("%s,%d,%.2f,%.3f,%.2f,%d,%.3f,%.3f,%b,%b%n", kind, n, put, get, get * 1e6 / LOOKUPS, p99, rc, ra, codeOk, arrOk);
                }
                // ---- alternative: sorted array + binary search ----
                double build = 0, get = 0, rc = 0, ins = 0;
                for (int r = 0; r < REPS + 2; r++) {
                    long t0 = System.nanoTime();
                    SortedArrayInventory sa = new SortedArrayInventory(meds);
                    long t1 = System.nanoTime();
                    for (String k : keys) { Medicine m = sa.get(k); if (m != null) sink += m.stock(); }
                    long t2 = System.nanoTime();
                    String rep = sa.codeOrderReport();
                    long t3 = System.nanoTime();
                    Random rnd = new Random(r);
                    for (int i = 0; i < 1000; i++)                                   // "MED1234567N" sorts right after MED1234567:
                        sa.insert(new Medicine(String.format("MED%07dN", rnd.nextInt(10_000_000)), "New", 1));   // lands mid-array
                    long t4 = System.nanoTime();
                    if (r >= 2) {
                        build += (t1 - t0) / (double) n; get += (t2 - t1) / 1e6; rc += (t3 - t2) / 1e6; ins += (t4 - t3) / 1000.0;
                        raw.printf("SortedArray,%d,%d,%.2f,%.3f,%.3f,NA%n", n, r - 1, (t1 - t0) / (double) n, (t2 - t1) / 1e6, (t3 - t2) / 1e6);
                    }
                    if (r == REPS + 1 && !codesOf(rep).equals(sorted)) throw new IllegalStateException("sorted array order");
                }
                build /= REPS; get /= REPS; rc /= REPS; ins /= REPS;
                System.out.printf("%-14s %-10s %10.1f %14.3f %12.1f %12s %12.3f %12s %10s %10s   insert one new: %,.0f ns%n",
                        "SortedArray", String.format("%,d", n), build, get, get * 1e6 / LOOKUPS, "-", rc, "n/a", true, "n/a", ins);
                sum.printf("SortedArray,%d,%.2f,%.3f,%.2f,,%.3f,,true,,insert_ns=%.0f%n", n, build, get, get * 1e6 / LOOKUPS, rc, ins);
                System.out.println();
            }
        }
        System.out.println("put ns/op for SortedArray = sort-based build cost per medicine (O(n log n) total)");
        System.out.println("Raw data: data/q4_raw.csv   Summary: data/q4_summary.csv   (checksum " + (sink & 0xff) + ")");
    }

    /** 99th percentile of individually timed lookups (includes ~20-30 ns timer overhead). */
    static long p99Lookup(Inventory inv, String[] keys) {
        long[] t = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            long a = System.nanoTime();
            Medicine m = inv.get(keys[i]);
            t[i] = System.nanoTime() - a;
            if (m != null) sink += 1;
        }
        Arrays.sort(t);
        return t[(int) (t.length * 0.99)];
    }

    static String indent(String s) { return s.lines().map(l -> "    " + l + "\n").reduce("", String::concat); }
}
