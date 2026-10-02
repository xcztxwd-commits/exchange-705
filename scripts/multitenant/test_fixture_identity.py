"""Explicit fixture selection must not weaken default, identity, or test-label checks."""
import json,unittest
from types import SimpleNamespace
from unittest.mock import patch
import mysql_migration as core

class FixtureIdentityTests(unittest.TestCase):
    def database(self,**kw):
        return SimpleNamespace(**dict(dict(test=True,database='mt705_stage4_test',container='stage4-source',identity={'container_id':'a'*64}),**kw))
    def test_new_fixture_requires_explicit_complete_id(self):
        for identity in [None,'a'*12,'b'*64]:
            with self.assertRaises(ValueError):core.require_fixture(self.database(),identity)
    def test_non_fixture_and_non_fixture_database_reject(self):
        for db in [self.database(test=False),self.database(database='production')]:
            with self.assertRaises(ValueError):core.require_fixture(db,'a'*64)
    def test_exact_live_id_and_label_accept(self):
        with patch.object(core,'run',return_value=SimpleNamespace(stdout=json.dumps([{'Id':'a'*64,'Config':{'Labels':{'com.gtcfesk.multitenant.test':'true'}}}]).encode())):
            core.require_fixture(self.database(),'a'*64)
    def test_replaced_container_or_changed_label_reject(self):
        for identifier,label in [('b'*64,'true'),('a'*64,'false')]:
            with patch.object(core,'run',return_value=SimpleNamespace(stdout=json.dumps([{'Id':identifier,'Config':{'Labels':{'com.gtcfesk.multitenant.test':label}}}]).encode())):
                with self.assertRaises(ValueError):core.require_fixture(self.database(),'a'*64)
    def test_legacy_default_unchanged(self):
        core.require_fixture(self.database(container=core.TEST_CONTAINER))

if __name__=='__main__':unittest.main()
