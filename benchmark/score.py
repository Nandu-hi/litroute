"""Scores LitRoute match results against the benchmark labels.

Usage: python3 benchmark/score.py LABELS.json NAME=scored_queue.json [NAME=scored_queue.json ...]

Each scored_queue.json is a ScoredQueueStore file: one entry per paper, status UNMATCHED or a student.
Per paper:
  right          matched to a target student
  acceptable     matched to an optional student
  wrong student  matched to another student when the paper has a target
  false match    matched when the paper should match no one (and not to an optional student)
  missed         has a target but was matched to no one
"""
import json
import sys


def score(labels, queue):
    by_title = {p['candidate']['title']: p for p in queue}
    counts = {k: 0 for k in ('right', 'acceptable', 'wrong student', 'false match', 'missed', 'rightly unmatched')}
    rows = []
    for paper in labels['papers']:
        p = by_title.get(paper['title'])
        target = {s[:3] for s in paper['target']}
        optional = {s[:3] for s in paper['optional']}
        if p is None:
            # Never reached M6: M2 drops notes (special issues, errata, ...) and they are not queued.
            print('  not in the results (counted as not matched): ' + paper['title'][:80], file=sys.stderr)
            p = {'status': 'UNMATCHED', 'profileName': '-', 'relevance': 0.0}
        matched = p['status'] != 'UNMATCHED'
        student = p['profileName'][:3]
        if not matched:
            outcome = 'missed' if target else 'rightly unmatched'
        elif student in target:
            outcome = 'right'
        elif student in optional:
            outcome = 'acceptable'
        elif target:
            outcome = 'wrong student'
        else:
            outcome = 'false match'
        counts[outcome] += 1
        rows.append((outcome, student if matched else '-', p['relevance'], paper['title']))
    matched = counts['right'] + counts['acceptable'] + counts['wrong student'] + counts['false match']
    with_target = sum(1 for p in labels['papers'] if p['target'])
    counts['matched'] = matched
    counts['precision'] = (counts['right'] + counts['acceptable']) / matched if matched else 0.0
    counts['recall'] = counts['right'] / with_target
    return counts, rows


def main():
    labels = json.load(open(sys.argv[1], encoding='utf-8'))
    results = {}
    for arg in sys.argv[2:]:
        name, path = arg.split('=', 1)
        results[name] = score(labels, json.load(open(path, encoding='utf-8')))
    names = list(results)
    keys = ['matched', 'right', 'acceptable', 'wrong student', 'false match', 'missed', 'rightly unmatched']
    print(f'{"":20}' + ''.join(f'{n:>18}' for n in names))
    for k in keys:
        print(f'{k:20}' + ''.join(f'{results[n][0][k]:>18}' for n in names))
    for k in ('precision', 'recall'):
        print(f'{k:20}' + ''.join(f'{results[n][0][k]:>17.0%} ' for n in names))
    for n in names:
        print(f'\n{n}: errors')
        for outcome, student, rel, title in results[n][1]:
            if outcome in ('wrong student', 'false match', 'missed'):
                print(f'  {outcome:14} {student:4} {rel:.2f}  {title[:90]}')


if __name__ == '__main__':
    main()
