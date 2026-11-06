# M6 measurements: why the numbers are what they are

The record behind M6's floors and weights, oldest first. The formulas are in [calc.md](calc.md), the rules in the [readme](readme.md). Since November 2026 matching is judged on the [benchmark](../../../../../../benchmark/README.md) (84 alert papers labelled from content), not on LitRoute's stored sent or dropped records: those were test clicks, not the professor's decisions.

## Floor 0.7, no quota (October 2026)

The professor's first reviewed batch had 31 alert papers. Under the old rule, every paper was queued and each student's top 3 above 0.5 were recommended. 20 of the 31 were dropped, and 15 of those had landed on RP4-mh-char, the broadest hazard profile. Five of them were notes: book reviews, a discussion, a closure and an erratum (one book review scored 0.96).

| Rule | Queued | Approved | Dropped |
|---|---|---|---|
| old: everything, top 3 per student ★ | 31 | 11 | 20 |
| research, relevance ≥ 0.5 | 17 | 11 | 6 |
| research, relevance ≥ 0.6 | 15 | 10 | 5 |
| research, relevance ≥ 0.7 | 11 | 9 (+ hazard-chain paper) | 1 |
| research, relevance ≥ 0.75 | 9 | 8 (+ hazard-chain paper) | 0 |

On the filed papers, 0.7 still matched 41 of 46 from a PDF or from a title and abstract, and 33 of 46 from a title alone. Going to 0.75 would have removed one more wrong paper but cost recall everywhere, so 0.7 was chosen. With full PDFs, `HYBRID` assigned 46 of 48 correctly with P@3 1.0. `SEMANTIC` alone got 43 and 0.81, and `LEXICAL` alone 43 and 0.88.

## Titles weigh more (October 2026)

Three papers the professor would not want missed had been left out. Two were known only by title, because Elsevier deposits no abstract: flood fragility of bridge piers (RP1) and compound geohazards of the Greater Bay Area (RP4). The third was a ground-motion orientation paper (RP8) whose title says more than its abstract. Three changes followed:

- **Title lift 0.6:** a paper is also scored from its title alone, and if that is higher, its relevance rises by 0.6 of the gap. The orientation paper went from 0.59 to 0.70 (it scores 0.85 from its title).
- **Title meaning weight 0.65:** a dozen words share few terms with anyone's papers.
- **Title floor 0.43:** bridge piers (0.61) and geohazards (0.44) got in, and an out-of-scope steels review (0.41) stayed out.

## PDF weighed equally with its abstract; short abstracts ignored (November 2026)

The first run over the client's 17 saved alerts produced 84 papers, 50 of them from one Bulletin of Earthquake Engineering table of contents. On whole text, general seismic papers (FRP columns, soft storeys, masonry retrofit) cleared 0.7 for RP1 or RP8, because any seismic body shares hundreds of words with those students' papers. What sets the students apart is in the titles and abstracts. Three changes were made together: a PDF is placed ½ by whole text and ½ by title and abstract; a paper goes to the student it is *most relevant* to; abstracts under 25 words count as none; special issues count as notes. Result: out-of-scope alert matches fell from 13 of 50 to 10 of 50, and filed papers matched from title and abstract went from 40/2 to 41/1 (right/wrong). Nothing else changed.

Ideas tried and rejected because each one lost filed or sent papers: a higher floor (at the time), more meaning weight for PDFs, a minimum meaning score, contrast with the runner-up, rarity-weighted words, centred embeddings (51 arXiv papers matched), and a student-theme check.

## Stricter floors: 0.85 / 0.6 (November 2026)

The user's rule is precision over recall: a paper should not reach a student it doesn't belong to, even if that means missing some that do. The wrong matches left were general papers from a student's field, and they sit as close to two or three earthquake students as the right papers do, so only relevance ranks them lower. Measured on the benchmark with the offline replica, which reproduces the Java scores to 1e-7:

| Floors (abstract or PDF / title) | PDF: matched, right, wrong | Precision, recall | T+A: matched, right, wrong | Precision, recall | Match-test right/wrong (PDF, T+A, title) |
|---|---|---|---|---|---|
| 0.7 / 0.43 | 36, 21, 12 | 67%, 88% | 36, 21, 11 | 69%, 88% | 44/1, 41/1, 36/5 |
| 0.8 / 0.55 | 23, 18, 4 | 83%, 75% | 23, 17, 4 | 83%, 71% | 41/0, 39/1, 35/3 |
| **0.85 / 0.6** | 16, 15, 1 | 94%, 62% | 16, 14, 1 | 94%, 58% | 41/0, 38/1, 35/3 |
| 0.88 / 0.7 | 13, 13, 0 | 100%, 54% | 13, 12, 0 | 100%, 50% | 38/0, 37/1, 35/3 |

None of the 32 arXiv negatives matched at any floor. Two other gates were tested at ≥ 94% precision, and neither kept more right papers: relevance against the *closest* other student (15 PDF / 13 T+A right), and coverage of a student's defining words (15 / 15). The title floor rests on only six title-only benchmark papers. The benchmark is small, its labels are not blind, and the floor was chosen on it, so re-measure as real decisions come in.

## SPECTER2 on title + abstract only (experiment, November 2026)

With `-Dlitroute.semantic=abstract`, meaning is computed on title + abstract alone, on both sides; TF-IDF still uses the whole text. It was compared in live runs over the 17 alerts (57 PDFs), with 294 reference papers re-embedded:

| | Default | Title + abstract only |
|---|---|---|
| Benchmark matched, right, false | 16, 15, 1 | 17, 16, 1 |
| Precision, recall | 94%, 62% | 94%, 67% |
| Match-test PDFs (48): best student right; matched right/wrong | 46; 40/0 | 46; 39/0 |
| arXiv matched (of 32) | 0 | 0 |

The two are level. The variant is cheaper (1 SPECTER2 pass per paper instead of up to 8), but it stays off until it shows a measured gain.

## Listed-author floors 0.5 / 0.36 (November 2026)

| Evidence | Let in | Left out |
|---|---|---|
| Abstract or PDF, 0.5 | rural recovery 0.64, landfill flowslides 0.56, retrofit adoption 0.55, coastal flooding 0.51 | outmigration 0.46, landslide detection 0.40, landfill stability 0.31, ceramic coating 0.29 |
| Title alone, 0.36 | steels under impact 0.41 | ceramic coating 0.34, all 32 arXiv papers (highest 0.35) |

No arXiv abstract reaches 0.5 (the highest is 0.43). From a full PDF, one does (quasi-kernels, 0.64), but a false match would also need a listed author. These floors rest on few papers, and the title floor on only five.
