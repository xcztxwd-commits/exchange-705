"""Small transport/scope checks, never production or capacity acceptance."""
import datetime as dt
import hashlib
import unittest
from types import SimpleNamespace
import mysql_migration as core
import controlled_migration as controlled
import hard_delete_before as cleanup

class Rows:
    def __init__(self,rows):self.rows=rows
    def query_stream(self,sql):return iter(self.rows)

class Checks(unittest.TestCase):
    def test_stream_exact_original_hash_contract(self):
        a,b='0'*64,'f'*64
        actual=core.row_fingerprints(Rows(['t\t'+a,'t\t'+a,'t\t'+b]),[],{'t':[],'empty':[]})
        self.assertEqual({'rows':3,'sha256':hashlib.sha256((a+'\n'+a+'\n'+b).encode()).hexdigest()},actual['t'])
        self.assertEqual(hashlib.sha256(b'').hexdigest(),actual['empty']['sha256'])
    def test_unordered_or_partial_failure_never_pass(self):
        with self.assertRaises(ValueError):core.row_fingerprints(Rows(['t\t'+'f'*64,'t\t'+'0'*64]),[],{'t':[]})
        class Failure:
            def query_stream(self,sql):
                yield 't\t'+'0'*64
                raise RuntimeError('transport failure')
        with self.assertRaises(RuntimeError):core.row_fingerprints(Failure(),[],{'t':[]})
    def test_unknown_timestamp_unit_rejected(self):
        cutoff=dt.datetime(2026,9,1,tzinfo=dt.timezone(dt.timedelta(hours=8)))
        self.assertIn('2026-08-31 16:00:00'.encode().hex(),cleanup.time_condition('created_at','datetime',cutoff))
        self.assertIn('>100000000000',cleanup.time_condition('received_at','ms',cutoff))
        with self.assertRaises(ValueError):cleanup.time_condition('version','counter',cutoff)
    def test_user_master_never_deleted(self):
        with self.assertRaises(ValueError):cleanup.deletion_sql({'protected':['user_account'],'selected':{'user_account':[]}})
    def test_children_before_parents_and_cycles_refused(self):
        self.assertEqual(['child','parent'],cleanup.delete_order(['parent','child'],[('child','parent',[('parent_id','id')])]))
        with self.assertRaises(ValueError):cleanup.delete_order(['a','b'],[('a','b',[]),('b','a',[])])
    def test_count_mismatch_forces_mysql_error_before_commit(self):
        sql=cleanup.deletion_sql({'protected':['user_account'],'selected':{'child':[{'key':['1']}]},'keys':{'child':['id']},'delete_order':['child']})
        self.assertLess(sql.index('ROW_COUNT()=1'),sql.index('COMMIT;'))
        self.assertIn('NOT NULL',sql);self.assertNotIn('FOREIGN_KEY_CHECKS',sql)
    def test_restore_preserves_source_defaults_and_rejects_business_target(self):
        source=SimpleNamespace(query=lambda sql:['latin1\tlatin1_swedish_ci'])
        calls=[]
        restore=SimpleNamespace(test=True,database='mt705_restore',create_empty=lambda:calls.append('CREATE'),
                                sql=lambda sql,database:calls.append(sql),query=source.query)
        controlled.create_restore_database(source,restore)
        self.assertEqual('CREATE',calls[0]);self.assertIn('CHARACTER SET latin1 COLLATE latin1_swedish_ci',calls[1])
        restore.test=False
        with self.assertRaises(ValueError):controlled.create_restore_database(source,restore)
        self.assertEqual(2,len(calls))
        with self.assertRaises(ValueError):controlled.database_defaults(SimpleNamespace(query=lambda sql:['latin1;DELETE\tlatin1_swedish_ci']))

if __name__=='__main__':unittest.main()
