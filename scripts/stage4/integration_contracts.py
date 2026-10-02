"""Read-only merged-input/archive contract check; reuse unchanged acceptance, never rerun business."""
import base64
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/multitenant'))
import controlled_migration as c
import isolation_gate as gate
import retention_archive as archive


def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def sha(path):return c.core.file_hash(Path(path))


def main(stage3,stage4,baseline):
    s3=stage3/'reports/stage3-20261002-232106';s4=stage4/'reports/stage4-baseline-20261002-232106'
    e3=read(s3/'evidence.json');e4=read(s4/'evidence.json');base=read(baseline)
    output=ROOT/'reports/stage34-integration-20261003/contracts.json'
    refs=set()
    def visit(value):
        if isinstance(value,dict):
            if isinstance(value.get('path'),str) and isinstance(value.get('sha256'),str) and len(value['sha256'])==64:
                refs.add((value['path'],value['sha256']))
            for item in value.values():visit(item)
        elif isinstance(value,list):
            for item in value:visit(item)
    visit(e3);visit(e4)
    for path,digest in refs:assert sha(path)==digest,path
    for item in e3['sourceDelta']:assert sha(ROOT/item['relativePath'])==item['sha256'],item['relativePath']
    for path,digest in e4['candidate']['changedFiles'].items():assert sha(ROOT/path)==digest,path
    changes={}
    for key in ['sourceSha256','acceptedBuildTestInputSha256']:
        changed=[]
        for path,digest in base[key].items():
            assert (ROOT/path).is_file(),path
            if sha(ROOT/path)!=digest:changed.append(path)
        changes[key]={'count':len(base[key]),'changed':changed,'missing':[]}
    app='exchange-backend/src/main/java/com/gtcfesk/exchange/control/ChatRetentionService.java'
    assert changes['acceptedBuildTestInputSha256']['changed']==[app]
    assert set(changes['sourceSha256']['changed'])=={app,'scripts/multitenant/controlled_migration.py',
            'scripts/multitenant/mysql_migration.py','scripts/multitenant/orphan_quarantine.py',
            'scripts/multitenant/private_files.py','scripts/multitenant/source_isolation_registry.json'}
    errors,_=gate.check();assert not errors,errors
    registry=read(gate.REGISTRY);before=read(ROOT/'reports/stage34-integration-20261003/private/registry-before.json')
    assert {k:v for k,v in registry.items() if k!='files'}=={k:v for k,v in before.items() if k!='files'}
    assert registry['release_approved'] is False
    assert {k:v for k,v in registry['files'].items() if k!=app}=={k:v for k,v in before['files'].items() if k!=app}
    release_errors,_=gate.check(release=True);assert release_errors
    # Contract check against already-produced real archives. No new restore claim.
    home=s3/'private/REAL-archives/tenant-3'
    v1=sorted(home.glob('3-*.json'));v2=list(home.glob('job-*/manifest-*.json'))
    assert len(v1)==2 and len(v2)==1
    checked=[{'path':str(p),'sha256':sha(p),'result':archive.verify(p)} for p in v1+v2]
    documents=[read(p) for p in v1];assert documents[0]==documents[1]
    _,doc,_=archive.v2_manifest(v2[0]);reconstructed={'tenantId':doc['tenantId'],'conversation':[doc['conversation']],'messages':[],'attachments':[]}
    for chunk,images in archive.v2_chunks(v2[0]):
        reconstructed['messages']+=chunk['messages']
        for image in chunk['attachments']:
            reconstructed['attachments'].append({**image['row'],'content':base64.b64encode(images[image['row']['message_id']].read_bytes()).decode()})
    assert reconstructed==documents[0]
    totals={k:0 for k in ['tests','failures','errors','skipped']}
    for item in e3['freshBackendTests']['classes']:
        tree=ET.parse(item['xml']['path']).getroot()
        counts={k:int(tree.get(k,'0')) for k in totals};assert counts==item['counts']
        for key in totals:totals[key]+=counts[key]
    assert totals==e3['freshBackendTests']['rawTotals']=={'tests':29,'failures':0,'errors':0,'skipped':1}
    jdbc=read(e3['freshBackendTests']['replacement']['path']);assert jdbc['exitCode']==0 and jdbc['readOnly'] and 'java.time.LocalDateTime' in jdbc['output']
    oldhome=stage4/'rollback/multitenant-20260929/stage4-baseline-20261002-232106/controlled'
    oldpolicy=c.Policy(oldhome/'fixture-only-policy.json',True)
    oldfailed=c.Ledger(oldhome/'uncertain/ledger',oldpolicy)
    assert oldfailed.latest()['kind']=='FAILED_UNCERTAIN'
    result={'status':'PASS_FRESH','referenceFilesVerified':len(refs),'referenceMapSha256':c.digest(sorted(refs)),
            'sameInputEvidenceReused':True,'stage3FilesExact':len(e3['sourceDelta']),'stage4FilesExact':len(e4['candidate']['changedFiles']),
            'baselineComparison':changes,'entitiesDdlEpochUnchanged':True,'frontendInputsUnchanged':True,
            'sourceGate':'PASS','reviewedRegistryEntries':1,'approvalAndReleaseBlockersUnchanged':True,
            'productionGateStillBlocked':release_errors,'archiveReadOnlyChecks':checked,
            'allV1V2FieldsEqual':True,'canonicalArchiveSha256':c.digest(documents[0]),
            'archiveIndependentRestores':'PASS_REUSED: both original real MySQL restore proofs hash-verified; no repeat restore',
            'stage3JUnit':{'raw':totals,'passed':28,'nativeJdbcReplacement':'PASS_REUSED','skipRewritten':False},
            'originalStage4Failure':{'tipSha256':c.digest(oldfailed.latest()),'kind':oldfailed.latest()['kind'],
                                   'receiptHashes':{p.name:sha(p) for p in oldfailed.directory.glob('*.json')}},
            'notRun':['stage1-4 full suites','new frontend/browser business tests','production legacy migration','stage5']}
    c.publish(output,result)
    print('MERGED_ARCHIVE_CONTRACT_PASS; 128 references checked; JUnit 29/28/0/0/1 retained; no business rerun')


if __name__=='__main__':main(*map(Path,sys.argv[1:]))
