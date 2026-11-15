# CSA09 – Assignment 2 (CO2): Strings, Collections, hashCode–equals, Generics & Comparators

| | |
|---|---|
| **Name** | `<YOUR NAME>` |
| **Register Number** | `<REGISTER NUMBER>` |
| **Java version** | OpenJDK 21 (compiles on JDK 17+) |
| **Report** | [`report/Assignment2_Report.pdf`](report/Assignment2_Report.pdf) (also `.docx` / `.md`) |

## Repository layout

```
QN/
├── *.java             source code (Complete-the-Code programs + experiment programs)
├── input/             input files (workloads, songs, scans, medicines, teams, patients, config)
├── data/*.csv         RAW experimental data – every repeated run is recorded
├── output/*.txt       console output of each program (compile + run commands at the top)
├── graphs/*.png       graphs drawn from data/*.csv
└── screenshots/*.png  output screenshots
report/                assignment report (pdf, docx, md)
run_all.sh             compiles and runs every program (about 8–10 minutes)
tools/                 make_graphs.py, render_screenshots.py, build_report.py
```

## How to compile and run

```bash
bash run_all.sh                 # everything; or e.g.  bash run_all.sh Q3 Q5
python3 tools/make_graphs.py    # rebuild graphs from the CSV files (needs matplotlib)
```

| Q | Compile (inside the folder) | Run |
|---|---|---|
| Q1 | `javac -d out TicketBenchmark.java SharedLogDemo.java` | `java -Xms512m -Xmx512m -cp out TicketBenchmark input/workloads.txt`<br>`java -cp out SharedLogDemo 4 50000 10` |
| Q2 | `javac -d out PlaylistBenchmark.java` | `java -Xms1g -Xmx1g -cp out PlaylistBenchmark input/songs.csv` |
| Q3 *(Complete the Code)* | `javac -d out_before before_todo/Attendance.java` then `javac -d out Attendance.java` | `java -cp out_before Attendance` (before TODO) / `java -cp out Attendance` (after) |
| Q3 | `javac -d out ContractLab.java` | `java -Xms1g -Xmx1g -cp out ContractLab input/scans.csv` |
| Q4 | `javac -d out PharmacyInventory.java` | `java -Xms1g -Xmx1g -cp out PharmacyInventory input/medicines_sample.csv` |
| Q5 *(Complete the Code)* | `javac -d out LeaderboardDemo.java` | `java -cp out LeaderboardDemo` |
| Q5 | `javac -d out RankingLab.java` | `java -Xms1g -Xmx1g -cp out RankingLab input/teams_ties.csv input/expected_ties.txt input/teams_adversarial.csv input/expected_adversarial.txt` |
| Q6 *(Complete the Code)* | `javac -d out Triage.java` | `java -cp out Triage` |
| Q6 | `javac -d out TriageLab.java` | `java -Xms1g -Xmx1g -cp out TriageLab input/patients_8.csv input/expected_8.txt` |
| Q7 | `javac -d out LeaderboardRedesign.java` | `java -Xms2g -Xmx2g -cp out LeaderboardRedesign input/experiment.txt` |

## Result of each question

| Q | Result |
|---|---|
| Q1 | `String +` is quadratic: each doubling of fields ≈ 4× time and memory (17 GB allocated at 3,200 fields vs 42 MB for StringBuilder); 323× slower on long tickets, negligible on a 12-field ticket. Shared log: StringBuilder corrupted 9/10 trials; StringBuffer kept length but interleaved lines in 2/10 trials. Recommendation: StringBuilder per ticket, one atomic append per finished line (or a concurrent queue) for the log. |
| Q2 | ArrayList wins for append, get(k) (≈25–38 ns at every size) and iteration (1 ns/song, ¼ the memory of LinkedList); front inserts grow to 8.8 µs at 100k. For the Up Next queue ArrayDeque is O(1) (≈20–27 ns) vs ArrayList 6.7 µs. |
| Q3 | **Complete the Code: 3 before TODO, 2 after (matches expected).** All 7 predicted set sizes confirmed (duplicates, mutated key, TreeSet inconsistent with equals). HashSet/HashMap ≈ 0.2 µs per scan, TreeSet 2 µs at 1M. |
| Q4 | All maps give < 4 µs p99 lookups up to 1M medicines; LinkedHashMap chosen (O(1) lookups + free arrival-order report; one sort for the code report). Sorted array insert grows to 191 µs at 1M. |
| Q5 | **Complete the Code output matches expected.** Generic `Leaderboard<T>` / `RankedBoard<T extends Rankable>` reused for teams, players, colleges. Subtraction comparator fails the adversarial set (24 transitivity violations); a non-zero comparator crashed TimSort in 5/50 sorts. |
| Q6 | **Complete the Code output matches expected;** 8-patient hand check matches. PQ: admit ≈ 45 ns flat, next 0.2–0.8 µs. Strict priority left 122 of 191 S5 patients untreated; ageing capped at level 3 cut that to 23 without affecting S1/S2. 5,000 patients/day: 1.7 µs per patient. |
| Q7 | Redesign of the live leaderboard (TreeSet + HashMap, order maintained instead of re-sorted): 1 update + top-10 at 1M teams 1.29 s → 5.3 µs (243,000×), 18.6 M → 40 comparisons, 0 mismatches vs the original, +30 % memory. |

Screenshots are rendered from the captured console output (`tools/render_screenshots.py`).
