"""Offline SQLite contract regressions; not a test of Android Binder or SQLiteOpenHelper."""
import re
import sqlite3
import unittest
from pathlib import Path

JAVA = (Path(__file__).resolve().parents[1] / 'src/dev/eta/acefan/DataProvider.java').read_text()
CREATE = re.search(r'String CREATE_DAILY = "([^\"]+)"', JAVA).group(1)
SELECT = re.search(r'String SELECT_DAILY = "([^\"]+)"', JAVA).group(1)
CHARGE_V1 = 'CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, version INTEGER NOT NULL)'
UPGRADE_V1 = re.search(r'"(ALTER TABLE config ADD COLUMN start_temp [^\"]+)"', JAVA).group(1)
DEFAULT = (0, 0, 33.0, 6, 1.0, 0.0, 0.0, 0.0)
VALUES = ('enabled', 'version', 'start_temp', 'min_level', 'w_battery', 'w_board', 'w_fan', 'w_mos')


def install_daily(db):
    db.execute(CREATE)
    db.execute('INSERT INTO daily_config VALUES(1,?,?,?,?,?,?,?,?)', DEFAULT)


def save(db, expected, row):
    return db.execute('UPDATE daily_config SET ' + ','.join(field + '=?' for field in VALUES)
                      + ' WHERE id=1 AND version=?', (*row, expected)).rowcount


class DailyBackendTests(unittest.TestCase):
    def test_both_migration_inputs_keep_charge_and_history(self):
        for start in (1, 2):
            with self.subTest(source_schema=start), sqlite3.connect(':memory:') as db:
                db.execute(CHARGE_V1)
                db.execute('INSERT INTO config VALUES(1,1,42)')
                db.execute('CREATE TABLE sample(id INTEGER PRIMARY KEY, wall INTEGER, elapsed INTEGER, json TEXT)')
                db.execute("INSERT INTO sample VALUES(7,10,20,'{}')")
                if start == 2:
                    db.execute(UPGRADE_V1)
                    db.execute('UPDATE config SET start_temp=37.5')
                with db:
                    if start < 2:
                        db.execute(UPGRADE_V1)
                    install_daily(db)
                initial = (1, 1, 42, 33.0 if start == 1 else 37.5)
                self.assertEqual(initial, db.execute('SELECT * FROM config').fetchone())
                self.assertEqual((7, 10, 20, '{}'), db.execute('SELECT * FROM sample').fetchone())
                self.assertEqual(DEFAULT, db.execute(SELECT).fetchone())
                self.assertEqual(1, save(db, 0, (1, 1, 36.5, 9, 55, 25, 15, 5)))
                self.assertEqual(initial, db.execute('SELECT * FROM config').fetchone())
                db.execute('UPDATE config SET enabled=0,version=43')
                self.assertEqual((1, 1, 36.5, 9, 55, 25, 15, 5), db.execute(SELECT).fetchone())

    def test_revision_compare_and_swap(self):
        with sqlite3.connect(':memory:') as db:
            install_daily(db)
            current = (1, 1, 36.5, 9, 50, 20, 0, 30)
            self.assertEqual(1, save(db, 0, current))
            self.assertEqual(0, save(db, 0, (1, 1, 20, 20, 100, 0, 0, 0)))
            self.assertEqual(current, db.execute(SELECT).fetchone())

    def test_database_rejects_invalid_profile(self):
        with sqlite3.connect(':memory:') as db:
            install_daily(db)
            for row in ((1, 1, 51, 6, 1, 0, 0, 0), (1, 1, 35, 0, 1, 0, 0, 0),
                        (1, 1, 35, 6, 0, 0, 0, 0), (1, 1, 35, 6, 1, -2, 0, 0),
                        (1, 1, 35, 6, 1, 1, 0, 101), (1, 1, 35, 6, 1, 1, 0, float('nan'))):
                with self.subTest(row=row), self.assertRaises(sqlite3.IntegrityError):
                    save(db, 0, row)
                self.assertEqual(DEFAULT, db.execute(SELECT).fetchone())
