# Matching benchmark

[alert-papers-2026-11-05.json](alert-papers-2026-11-05.json) labels the 84 papers from the 17 saved alert emails of 5 November 2026. Each paper has:

- `target`: the student(s) it belongs to (`[]` = no one);
- `optional`: a student it may also go to without counting as an error;
- `reason`: one line saying why.

The labels were judged from title and abstract against the topics in the students' folders, without using any LitRoute data (scores, sent or dropped records, `match-test`). 24 papers have a target and 60 have none. The labels were written after LitRoute's scores had been seen, so they are **not blind**.

```
python3 benchmark/score.py benchmark/alert-papers-2026-11-05.json NAME=path/to/scored_queue.json [NAME=...]
```

The script prints, for each run:

- right / acceptable / wrong-student / false / missed counts;
- precision = (right + acceptable) / matched;
- recall = right / 24;
- the list of errors.

A labelled paper missing from the queue (for example a note M2 dropped) counts as not matched, and the script names it.

## Results (5 November 2026)

| | Matched | Right | Accept. | Wrong student | False | Missed | Precision | Recall |
|---|---|---|---|---|---|---|---|---|
| Hybrid, full PDF, floor 0.7 | 36 | 21 | 3 | 0 | 12 | 3 | 67% | 88% |
| Hybrid, title + abstract, floor 0.7 | 36 | 21 | 4 | 0 | 11 | 2 | 69% | 88% |
| SPECTER2 only, title + abstract, 0.7 | 27 | 17 | 1 | 3 | 6 | 4 | 67% | 71% |
| SPECTER2 only, full PDF, 0.7 | 26 | 16 | 1 | 3 | 6 | 5 | 65% | 67% |
| **Hybrid, full PDF, floor 0.85 / 0.6** (current) | 16 | 15 | 0 | 0 | 1 | 9 | **94%** | 62% |
| Hybrid, title + abstract, 0.85 / 0.6 | 16 | 14 | 1 | 0 | 1 | 10 | 94% | 58% |
| Live run, OpenAlex down (47 of 83 title-only) | 25 | 16 | 1 | — | 8 | 8 | 68% | 67% |
| Live re-run, SPECTER2 on title + abstract only | 17 | 16 | — | — | 1 | 8 | 94% | 67% |

- "Hybrid" = SPECTER2 + TF-IDF.
- "Full PDF" = whole text weighed equally with the title and abstract.
- "Title + abstract" = no PDF text; the abstract is taken from the PDF when the alert had none.

**Notes:**

- The current default's one false match is a seismic design paper on infilled RC frames (RP8, 0.86). The title-and-abstract-only variant's is a soil-structure interaction vulnerability paper (RP1, 0.87).
- When OpenAlex was down, six of the eight false matches were title-only general seismic papers at relevance 0.62–0.80. On those title-only papers a title floor of 0.7 would match 10 (8 right) instead of 18 (10 right).
- The floor was chosen on this benchmark, so these figures flatter it.

The reasoning behind each change is in [scoring/measurements.md](../src/main/java/com/litroute/scoring/measurements.md).
