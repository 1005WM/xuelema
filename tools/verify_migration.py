"""Exercise the actual migration SQL against exported Room schemas using desktop SQLite.

This complements, and does not replace, ExamMigrationTest on Android.
"""
from pathlib import Path
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / 'app/schemas/com.homeworkassistant.data.local.AppDatabase'


def database(version):
    connection = sqlite3.connect(':memory:')
    schema = json.loads((SCHEMAS / f'{version}.json').read_text(encoding='utf-8'))['database']
    for entity in schema['entities']:
        connection.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return connection


def columns(connection, table):
    return connection.execute(f'PRAGMA table_info("{table}")').fetchall()


old = database(1)
expected = database(5)
old.execute("INSERT INTO homework (accountId,id,courseId,courseName,title,detailUrl,status) "
            "VALUES ('account','old','course','Math','Cached homework','https://i.chaoxing.com','NOT_SUBMITTED')")
source = (ROOT / 'app/src/main/java/com/homeworkassistant/data/local/ExamStorage.kt').read_text(encoding='utf-8')
source += (ROOT / 'app/src/main/java/com/homeworkassistant/data/local/CourseHomeworkStorage.kt').read_text(encoding='utf-8')
statements = re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)', source)
assert len(statements) == 8, 'Expected eight migration statements; review the verifier when migration changes'
for statement in statements[:2]:
    old.execute(json.loads('"' + statement + '"'))
old.execute("INSERT INTO exams VALUES ('account','old-exam','Math exam','Unknown',NULL,NULL,'60','Ended','Submitted','90','Online','SUBMITTED')")
for statement in statements[2:]:
    old.execute(json.loads('"' + statement + '"'))
assert old.execute('SELECT id,status FROM homework').fetchall() == [('old', 'NOT_SUBMITTED')]
assert old.execute('SELECT id,courseId,classId,courseName FROM exams').fetchall() == [('old-exam', None, None, None)]
for table in ['homework', 'sync_metadata', 'exams', 'exam_sync', 'course_work_cache']:
    assert columns(old, table) == columns(expected, table), f'Migrated columns differ: {table}'
old.close()
expected.close()
print('Migration 1->2->3->4->5 preserves homework/exams and matches Room v5 columns/keys; Android runtime test still required.')
