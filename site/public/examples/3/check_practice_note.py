from pathlib import Path
import sys

required = (
    'Users can access only their own drafts.',
    'Sending invoices and collecting payments are out of scope.',
    'How long unfinished drafts should be kept is undecided.',
)
try:
    lines = Path('practice-note.md').read_text(encoding='utf-8').splitlines()
except (OSError, UnicodeError) as error:
    print(f'FAIL: cannot read practice-note.md: {error}')
    sys.exit(1)

missing = [line for line in required if line not in lines]
if missing:
    print('FAIL: missing exact required lines:')
    for line in missing:
        print(f'  {line!r}')
    sys.exit(1)

print('PASS: required practice lines are present')
