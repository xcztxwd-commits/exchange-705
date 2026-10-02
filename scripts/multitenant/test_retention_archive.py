import base64,copy,hashlib,json,tempfile,unittest
from pathlib import Path
import retention_archive as tool
class RetentionArchiveTest(unittest.TestCase):
 def body(self):
  binary=tool.PNG+b'synthetic-fixture';image=hashlib.sha256(binary).hexdigest()
  canonical=[7,'USER',3,'synthetic','fixture-request','local fixture',image,'2026-09-30T00:00:00Z','']
  message={'id':9,'tenant_id':2,'conversation_id':7,'sender':'USER','sender_id':3,'sender_name':'synthetic','request_id':'fixture-request','text':'local fixture','image':True,'image_hash':image,'created_at':1790726400000,'previous_hash':'','hash':hashlib.sha256(json.dumps(canonical,separators=(',',':')).encode()).hexdigest()}
  return {'tenantId':2,'conversation':[{'id':7,'tenant_id':2,'status':'CLOSED','last_hash':message['hash']}],'messages':[message],'attachments':[{'tenant_id':2,'message_id':9,'content':base64.b64encode(binary).decode()}]}
 def verify(self,body):
  with tempfile.TemporaryDirectory() as directory:
   raw=json.dumps(body).encode();file=Path(directory)/('7-'+hashlib.sha256(raw).hexdigest()+'.json');file.write_bytes(raw);return tool.verify(file)
 def test_full_chain_and_attachment_are_checked_but_db_restore_not_claimed(self):
  result=self.verify(self.body());self.assertEqual(1,result['messages']);self.assertTrue(all(result['checks'].values()));self.assertFalse(result['databaseRestored'])
 def test_tampered_chain_last_hash_scope_duplicate_and_missing_image_all_reject(self):
  for change in (lambda b:b['messages'][0].update(text='changed'),lambda b:b['conversation'][0].update(last_hash='wrong'),lambda b:b['messages'][0].update(tenant_id=3),lambda b:b['messages'].append(copy.deepcopy(b['messages'][0])),lambda b:b['attachments'].clear(),lambda b:b['attachments'].append(copy.deepcopy(b['attachments'][0])),lambda b:b['attachments'][0].update(content=base64.b64encode(b'not-PNG').decode())):
   body=self.body();change(body)
   with self.assertRaises(ValueError):self.verify(body)
 def test_initialize_never_creates_restore_marker(self):
  with tempfile.TemporaryDirectory() as parent:
   directory=Path(parent)/'private';tool.initialize(directory);self.assertTrue(directory.is_dir());self.assertFalse((directory/'.retention-restore-verified').exists());self.assertFalse((directory/'.retention-restore-report.json').exists());self.assertEqual([],list(directory.iterdir()))
 def test_duplicate_json_fields_and_unzoned_time_reject(self):
  with self.assertRaises(ValueError):json.loads('{"tenantId":1,"tenantId":2}',object_pairs_hook=tool.unique_pairs)
  with self.assertRaises(ValueError):tool.instant('2026-09-30T00:00:00')

 def test_system_welcome_uses_real_zero_sender_without_inventing_actor(self):
  body=self.body();m=body['messages'][0];m.update(sender='SYSTEM',sender_id=0,sender_name='system',image=False,image_hash='');body['attachments']=[]
  canonical=[7,'SYSTEM',0,'system',m['request_id'],m['text'],'',tool.instant(m['created_at']),'']
  m['hash']=hashlib.sha256(json.dumps(canonical,separators=(',',':')).encode()).hexdigest();body['conversation'][0]['last_hash']=m['hash']
  self.assertTrue(self.verify(body)['checks']['messageChain'])
  m['sender']='USER'
  with self.assertRaises(ValueError):self.verify(body)
 def test_only_exact_independent_isolated_restore_targets(self):
  from types import SimpleNamespace
  source=SimpleNamespace(identity={'server_uuid':'source'})
  for test,identity,name in [(False,{'test_instance':False,'server_uuid':'other'},'mt705_restore_x'),(True,{'test_instance':True,'server_uuid':'source'},'mt705_restore_x'),(True,{'test_instance':True,'server_uuid':'other'},'production')]:
   with self.assertRaises(ValueError):tool.restore_target(source,SimpleNamespace(test=test,identity=identity,database=name))
  self.assertEqual('mt705_restore_x',tool.restore_target(source,SimpleNamespace(test=True,identity={'test_instance':True,'server_uuid':'other'},database='mt705_restore_x'))['targetDatabase'])
 def test_schema_columns_are_exact_and_binary_bytes_are_not_text(self):
  columns=[['message_id','bigint','NO'],['tenant_id','bigint','NO'],['content','longblob','NO']]
  raw=tool.PNG+b'fixture';row={'message_id':9,'tenant_id':2,'content':base64.b64encode(raw).decode()}
  self.assertEqual(raw,tool.typed_row(row,columns)['content'])
  for wrong in ({**row,'id':11},{k:v for k,v in row.items() if k!='tenant_id'},{**row,'content':'!bad-base64'}):
   with self.assertRaises(ValueError):tool.typed_row(wrong,columns)
 def test_epoch_timestamp_is_utc_and_null_required_field_rejects(self):
  row={'created_at':1790726400000};columns=[['created_at','datetime','NO']]
  self.assertEqual('2026-09-30 00:00:00.000000',tool.typed_row(row,columns)['created_at'])
  with self.assertRaises(ValueError):tool.typed_row({'created_at':None},columns)


class ChunkedRetentionArchiveTest(unittest.TestCase):
 def fixture(self,directory,count=201):
  import uuid
  directory=Path(directory);job=str(uuid.uuid4());previous='';all_messages=[];binary=tool.PNG+b'fixture';image_hash=hashlib.sha256(binary).hexdigest()
  for i in range(1,count+1):
   image=i%100==0;sender='SYSTEM' if i==1 else 'USER';sender_id=0 if i==1 else 3;created='2024-01-01T00:00:00.123456Z'
   canonical=[7,sender,sender_id,'测试',f'r-{i}',f'本地{i}',image_hash if image else '',created,previous]
   current=hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
   all_messages.append(dict(id=i,tenant_id=2,conversation_id=7,sender=sender,sender_id=sender_id,sender_name='测试',request_id=f'r-{i}',text=f'本地{i}',image=image,image_hash=image_hash if image else '',created_at=created,previous_hash=previous,hash=current));previous=current
  references=[]
  for start in range(0,count,100):
   rows=all_messages[start:start+100];attachments=[]
   for row in rows:
    if row['image']:
     name=f'image-{row["id"]}-{image_hash}.png';(directory/name).write_bytes(binary);attachments.append({'row':{'message_id':row['id'],'tenant_id':2},'file':name,'sha256':image_hash,'bytes':len(binary)})
   sequence=len(references)+1;chunk={'format':'retention-chunk-v2','jobId':job,'tenantId':2,'conversationId':7,'sequence':sequence,'afterId':all_messages[start-1]['id'] if start else 0,'firstId':rows[0]['id'],'lastId':rows[-1]['id'],'previousHash':rows[0]['previous_hash'],'lastHash':rows[-1]['hash'],'messages':rows,'attachments':attachments}
   raw=json.dumps(chunk,ensure_ascii=False,separators=(',',':')).encode();digest=hashlib.sha256(raw).hexdigest();name=f'chunk-{sequence:06}-{digest}.json';(directory/name).write_bytes(raw)
   references.append({'file':name,'sha256':digest,'sequence':sequence,'messages':len(rows),'firstId':rows[0]['id'],'lastId':rows[-1]['id'],'previousHash':rows[0]['previous_hash'],'lastHash':rows[-1]['hash']})
  manifest={'format':'retention-chunks-v2','jobId':job,'tenantId':2,'conversation':{'id':7,'tenant_id':2,'user_id':3,'status':'CLOSED','active_user_id':None,'last_hash':previous},'snapshotSha256':'a'*64,'messages':count,'attachmentBytes':(count//100)*len(binary),'lastId':count,'lastHash':previous,'chunks':references}
  return self.write_manifest(directory,manifest),manifest
 def write_manifest(self,directory,body):
  raw=json.dumps(body,ensure_ascii=False,separators=(',',':')).encode();f=Path(directory)/('manifest-'+hashlib.sha256(raw).hexdigest()+'.json');f.write_bytes(raw);return f
 def test_three_bounded_chunks_preserve_microseconds_full_chain_and_binary_bytes(self):
  with tempfile.TemporaryDirectory() as directory:
   file,_=self.fixture(directory);result=tool.verify(file);self.assertEqual((201,3,2),(result['messages'],result['chunks'],result['attachments']));self.assertTrue(all(result['checks'].values()));self.assertFalse(result['databaseRestored']);self.assertEqual(3,result['toolVersion']);self.assertEqual([100,100,1],[len(chunk['messages']) for chunk,_ in tool.v2_chunks(file)])
 def test_inner_chain_tamper_rejects_even_with_rehashed_chunk_and_manifest(self):
  with tempfile.TemporaryDirectory() as directory:
   file,manifest=self.fixture(directory);ref=manifest['chunks'][0];chunk=json.loads((Path(directory)/ref['file']).read_bytes());chunk['messages'][3]['text']='tampered';raw=json.dumps(chunk,ensure_ascii=False,separators=(',',':')).encode();digest=hashlib.sha256(raw).hexdigest();ref.update(file=f'chunk-000001-{digest}.json',sha256=digest);(Path(directory)/ref['file']).write_bytes(raw);file=self.write_manifest(directory,manifest)
   with self.assertRaisesRegex(ValueError,'full message chain'):tool.verify(file)
 def test_scope_sequence_final_count_and_path_injection_all_reject(self):
  changes=[lambda m:m.update(tenantId=3),lambda m:m['chunks'][0].update(sequence=2),lambda m:m.update(messages=202),lambda m:m.update(lastHash='f'*64),lambda m:m['chunks'][0].update(file='../chunk.json'),lambda m:m['chunks'].append(copy.deepcopy(m['chunks'][0]))]
  for change in changes:
   with tempfile.TemporaryDirectory() as directory:
    _,manifest=self.fixture(directory);change(manifest);file=self.write_manifest(directory,manifest)
    with self.assertRaises(ValueError):tool.verify(file)
 def test_missing_or_corrupt_png_preserves_not_verified(self):
  for missing in (False,True):
   with tempfile.TemporaryDirectory() as directory:
    file,manifest=self.fixture(directory);chunk=json.loads((Path(directory)/manifest['chunks'][0]['file']).read_bytes());image=Path(directory)/chunk['attachments'][0]['file']
    if missing:image.unlink()
    else:image.write_bytes(tool.PNG+b'tampered')
    with self.assertRaises(ValueError):tool.verify(file)
 def test_empty_complete_conversation_is_valid_but_no_synthetic_message_added(self):
  with tempfile.TemporaryDirectory() as directory:
   file,_=self.fixture(directory,0);r=tool.verify(file);self.assertEqual((0,0,0),(r['messages'],r['chunks'],r['attachments']))

if __name__=='__main__':unittest.main()
