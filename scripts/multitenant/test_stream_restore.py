"""Streaming transport checks; not native MySQL restore evidence."""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import mysql_migration as core

class StreamRestoreTests(unittest.TestCase):
    def test_file_transport_does_not_materialize_sql(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'fixture.sql';path.write_bytes(b'SELECT 1;')
            db=object.__new__(core.Database);db.test=True;db.container='offline-fixture';db.database='mt705_offline'
            def invoke(command,*,stdin,capture_output):
                self.assertEqual(db.command(),command);self.assertTrue(capture_output)
                self.assertEqual(b'SELECT 1;',stdin.read())
                return SimpleNamespace(returncode=0)
            with patch.object(core.subprocess,'run',side_effect=invoke),patch.object(Path,'read_text',side_effect=AssertionError):
                self.assertEqual(0,db.restore_file(path).returncode)

    def test_business_restore_rejected_before_open(self):
        db=object.__new__(core.Database);db.test=False
        with patch.object(Path,'open',side_effect=AssertionError),self.assertRaises(ValueError):db.restore_file('unused.sql')

    def test_transport_failure_not_pass_or_sensitive_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'fixture.sql';path.write_bytes(b'SELECT 1;')
            db=object.__new__(core.Database);db.test=True;db.container='offline-fixture';db.database='mt705_offline'
            with patch.object(core.subprocess,'run',return_value=SimpleNamespace(returncode=1)),self.assertRaisesRegex(RuntimeError,'Snapshot restore failed'):
                db.restore_file(path)

if __name__=='__main__':unittest.main()
