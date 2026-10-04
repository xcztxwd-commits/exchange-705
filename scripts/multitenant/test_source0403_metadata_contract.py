"""Offline preservation-contract checks. No MySQL/DDL/restore/approval success evidence.
Review/run against the candidate helper: set PYTHONPATH to ROOT/scripts/multitenant,
and SOURCE0403_REVIEW_CANDIDATE to the private controlled_migration candidate path.
When reviewed into scripts/multitenant, the normal controlled_migration import applies.
"""
import importlib.util
import os
from pathlib import Path
import unittest
candidate=os.environ.get('SOURCE0403_REVIEW_CANDIDATE')
if candidate:
    spec=importlib.util.spec_from_file_location('source0403_review_tool',Path(candidate))
    tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
else:
    import controlled_migration as tool

OLD=[{'name':'V2026100402__joint_s4_history_projection.sql','sha256':'a'*64}]
CURRENT=OLD+[{'name':'V2026100403__source_history_input_revision.sql','sha256':'b'*64}]
class Db:
    def __init__(self,rows=(),count='0'):
        self.rows=list(rows);self.count=count;self.sql=[]
    def query(self,sql):
        self.sql.append(sql)
        return [self.count] if sql.startswith('SELECT COUNT(*)') else self.rows

class Source0403MetadataContractTests(unittest.TestCase):
    def test_only_exact_reviewed_additive0403_tail_receives_append_contract(self):
        self.assertEqual(tool.SOURCE0403_METADATA,tool.metadata_append_contract(1,CURRENT))
        for start,files in [(0,CURRENT),(1,OLD),(2,CURRENT),(1,CURRENT+[{'name':'V2026100404__unreviewed.sql'}]),(1,OLD+[{'name':'V2026100403__other.sql'}])]:
            self.assertIsNone(tool.metadata_append_contract(start,files))
    def test_existing0403_row_never_becomes_an_inferred_completed_plan(self):
        tool.metadata_absent_before_plan(Db(count='0'),tool.SOURCE0403_METADATA)
        for count in ['1','2','NULL','']:
            with self.assertRaises(ValueError):tool.metadata_absent_before_plan(Db(count=count),tool.SOURCE0403_METADATA)
    def test_only_exact_one_inactive_current_epoch_row_is_accepted(self):
        db=Db(count='1');tool.metadata_receipt_after_phase(db,tool.SOURCE0403_METADATA)
        self.assertIn('version=2026100403 AND minimum_application_epoch=2026100403 AND business_activation_ready=0 AND applied_at IS NOT NULL',db.sql[0])
        for count in ['0','2','NULL','']:
            with self.assertRaises(ValueError):tool.metadata_receipt_after_phase(Db(count=count),tool.SOURCE0403_METADATA)
    def test_preservation_hashes_every_old_column_and_only_excludes_new_metadata_version(self):
        db=Db(['tenant_schema_version\t'+'a'*64,'asset_account\t'+'b'*64])
        columns={'tenant_schema_version':['version','applied_at','minimum_application_epoch','business_activation_ready'],'asset_account':['id','balance']}
        result=tool.preserved(db,columns,tool.SOURCE0403_METADATA)
        sql=db.sql[0];self.assertEqual(1,sql.count(' WHERE version<>2026100403'))
        self.assertIn('HEX(CAST(`applied_at` AS BINARY))',sql);self.assertIn('HEX(CAST(`business_activation_ready` AS BINARY))',sql)
        self.assertIn('FROM `asset_account`;',sql);self.assertNotIn('FROM `asset_account` WHERE',sql)
        self.assertEqual(1,result['tenant_schema_version']['rows']);self.assertEqual(1,result['asset_account']['rows'])
    def test_original_duplicate_metadata_rows_remain_multiset_sensitive(self):
        columns={'tenant_schema_version':['version','applied_at']};row='tenant_schema_version\t'+'a'*64
        two=tool.preserved(Db([row,row]),columns,tool.SOURCE0403_METADATA)
        one=tool.preserved(Db([row]),columns,tool.SOURCE0403_METADATA)
        self.assertEqual(2,two['tenant_schema_version']['rows']);self.assertNotEqual(two,one)
    def test_additive0403_does_not_allow_legacy_session_or_deposit_value_transforms(self):
        db=Db();tool.preserved(db,{'user_account':['current_token'],'deposit_record':['source','order_no','account_type']},tool.SOURCE0403_METADATA)
        self.assertIn('HEX(CAST(`current_token` AS BINARY))',db.sql[0]);self.assertNotIn('COALESCE(',db.sql[0]);self.assertNotIn('CAST(NULL',db.sql[0])
    def test_no_contract_preserves_original_query_shape_and_all_metadata_rows(self):
        db=Db();tool.preserved(db,{'tenant_schema_version':['version']})
        self.assertNotIn(' WHERE ',db.sql[0])
    def test_any_altered_metadata_contract_is_rejected_before_fingerprint_sql(self):
        for contract in [dict(tool.SOURCE0403_METADATA,version=2026100404),dict(tool.SOURCE0403_METADATA,business_activation_ready=True),{'table':'asset_account'}]:
            db=Db()
            with self.assertRaises(ValueError):tool.preserved(db,{'tenant_schema_version':['version']},contract)
            self.assertEqual([],db.sql)
if __name__=='__main__':unittest.main()
