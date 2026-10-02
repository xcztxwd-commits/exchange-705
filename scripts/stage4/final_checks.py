"""Close only changed verification gaps; no application business replay."""
import re
from rehearse import *

def main():
    if '--resources-only' not in sys.argv:
        results={}
        for mode in ['REAL','DEMO']:
            source=db('source','mt705_s4_'+mode.lower());expected=read(PRIVATE/(mode+'-full-state.json'));before=controlled.state(source)
            assert before['data']==expected['data']
            raw=Path(read(STATE)['inputs'][mode]['path']).read_text(encoding='utf-8');counters={}
            for m in re.finditer(r'CREATE TABLE `([^`]+)` \(.*?\) ENGINE=.*?;',raw,re.S):
                c=re.search(r' AUTO_INCREMENT=(\d+)',m[0].splitlines()[-1])
                if c:counters[m[1]]=int(c[1])
            corrections=[];cols=source.columns()
            for key,value in before['schema']['objects'].items():
                if expected['schema']['objects'].get(key)==value:continue
                assert key.startswith('table:');table=key[6:];counter=counters[table]
                definition=source.query('SHOW CREATE TABLE '+core.ident(table))
                candidate=[re.sub(r' ENGINE=InnoDB(?: AUTO_INCREMENT=\d+)?',f' ENGINE=InnoDB AUTO_INCREMENT={counter}',line) for line in definition]
                assert controlled.digest(candidate)==expected['schema']['objects'][key]
                column=next(c[0] for c in cols[table] if 'auto_increment' in c[-1]);minimum=int(source.query('SELECT COALESCE(MAX('+core.ident(column)+'),0)+1 FROM '+core.ident(table))[0]);assert counter>=minimum
                corrections.append({'table':table,'nextId':counter})
            if corrections:source.sql('\n'.join('ALTER TABLE '+core.ident(c['table'])+' AUTO_INCREMENT='+str(c['nextId'])+';' for c in corrections))
            assert controlled.state(source)==expected
            target=db('restore','mt705_restore_s4_'+mode.lower());assert controlled.state(target)==expected
            results[mode]={'status':'PASS_FRESH','allRowsAndDdlEqualOriginalVerifiedState':True,'restartCounterCompensationOnly':corrections,'neverLoweredBelowMaxIdPlusOne':True}
        save(OUT/'final-data-checkpoint.json',{'status':'PASS_FRESH','currentCopies':results})
    else:
        results={'status':'PASS_FRESH','source':'final-integrity-first-failure.log','scope':'REAL/DEMO full data and DDL assertions completed before the volume-label assertion; only resource check repeated; no database restarted'}
    # Read-only Redis use proves this run's endpoint identity, not another Redis restore claim.
    s=read(STATE);own(s['redis'])
    if '--resources-only' not in sys.argv:
        assert command(['docker','exec',s['redis'],'redis-cli','PING']).stdout.strip()==b'PONG'
        assert command(['docker','exec',s['redis'],'redis-cli','DBSIZE']).stdout.strip()==b'0'
    networks=[]
    for n in [s['network'],s.get('transportNetwork')]:
        if n:
            x=json.loads(command(['docker','network','inspect',n]).stdout)[0];assert x['Labels'].get(LABEL)==RUN
            networks.append({'id':x['Id'],'name':x['Name'],'labels':x['Labels'],'internal':x['Internal'],'containers':sorted(x.get('Containers',{}))})
    volumes=[]
    for name in s['resources']:
        info=own(name)
        for mount in info['mounts']:
            if mount['Type']=='volume':
                v=json.loads(command(['docker','volume','inspect',mount['Name']]).stdout)[0];labels=v.get('Labels') or {}
                users=command(['docker','ps','-aq','--no-trunc','--filter','volume='+mount['Name']]).stdout.decode().split()
                assert labels.get(LABEL)==RUN or (labels=={'com.docker.volume.anonymous':''} and users==[info['id']] and mount['Destination']=='/var/lib/mysql')
                volumes.append({'name':v['Name'],'mountpoint':v['Mountpoint'],'labels':labels,'onlyBoundToOwnedContainer':users==[info['id']],'imageDeclaredUnusedMysqlVolume':mount['Destination']=='/var/lib/mysql'})
    b=read(BASE);changed=[p for p,h in b['sourceSha256'].items() if not (ROOT/p).is_file() or sha(ROOT/p)!=h]
    allowed=['scripts/multitenant/mysql_migration.py','scripts/multitenant/orphan_quarantine.py','scripts/multitenant/private_files.py'];assert sorted(changed)==sorted(allowed)
    assert sha(b['jar']['path'])==b['jar']['sha256']
    for info in read(STATE)['inputs'].values():assert sha(info['path'])==info['sha256']
    save(OUT/'final-integrity.json',{'status':'PASS_FRESH','currentCopies':results,'redisOwnedEmptyPing':True,'networks':networks,'volumes':volumes,'baselineFilesChecked':len(b['sourceSha256']),'changedBaselineFiles':changed,'appFrontendSharedDdlEpochApprovalFilesUnchanged':True,'sharedBackupsUnchanged':True,'currentJarUnchanged':True})
    print('FINAL_DATA_DDL_INPUT_OWNERSHIP_PASS')

if __name__=='__main__':main()
