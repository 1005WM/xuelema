"""Aggregate XieLeMa.ReadTiming logcat or JUnit output; never needs URLs or credentials."""
import argparse
from collections import defaultdict
from pathlib import Path
import re
import sys

parser = argparse.ArgumentParser()
parser.add_argument('file', nargs='?', help='UTF-8 log; omit to read stdin')
args = parser.parse_args()
raw = Path(args.file).read_text(encoding='utf-8-sig', errors='replace') if args.file else sys.stdin.read()
rows = defaultdict(list)
for stage, kind, millis, success in re.findall(r'stage=(\w+) kind=(\w+) elapsed_ms=([\d.]+) success=(true|false)', raw):
    rows[stage, kind].append((float(millis), success == 'true'))
if not rows:
    raise SystemExit('No timing samples found')
print('stage/kind,count,total_ms,mean_ms,max_ms,failed')
for (stage, kind), values in sorted(rows.items()):
    durations = [value for value, _ in values]
    print(f'{stage}/{kind},{len(values)},{sum(durations):.3f},{sum(durations)/len(values):.3f},{max(durations):.3f},{sum(not ok for _, ok in values)}')
network = sum(ms for (stage, _), values in rows.items() if stage == 'network_body' for ms, _ in values)
parsing = sum(ms for (stage, _), values in rows.items() if stage == 'html_parse' for ms, _ in values)
if network + parsing:
    print(f'Network vs HTML parse accumulated time: {network/(network+parsing):.2%} / {parsing/(network+parsing):.2%}')
print('Stage totals are accumulated work, not wall time. Queue and throttle waits are reported separately.')
