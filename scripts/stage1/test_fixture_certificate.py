"""Native Prepare/absent-Remove contract checks. Never Install or add a Root CA.
Synthetic directories contain only a public 30-day CA, run metadata and test evidence.
"""
import argparse, datetime as dt, hashlib, json, os, subprocess, sys
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT/'scripts/stage1/Fixture-Certificate.ps1'
STORE = r"""
$store=[Security.Cryptography.X509Certificates.X509Store]::new('Root',[Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
try {
 $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
 $items=@(foreach($certificate in $store.Certificates){
  $sha=[Security.Cryptography.SHA256]::Create()
  try {$hash=([BitConverter]::ToString($sha.ComputeHash($certificate.RawData))).Replace('-','')} finally {$sha.Dispose()}
  [pscustomobject]@{thumbprint=$certificate.Thumbprint.ToUpperInvariant();certificateSha256=$hash}
 })
 ConvertTo-Json -InputObject @($items|Sort-Object thumbprint,certificateSha256) -Compress
} finally {$store.Close();$store.Dispose()}
"""
AST = r"""
$errors=$null;$tokens=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($env:STAGE1_CERT_HELPER,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Certificate helper parse errors'}
$starts=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and $n.Static -and $n.Expression.Extent.Text -eq '[Diagnostics.Process]' -and $n.Member.Value -eq 'Start'},$true))
$conditions=@(foreach($start in $starts){$parent=$start.Parent;while($parent -and $parent -isnot [Management.Automation.Language.IfStatementAst]){$parent=$parent.Parent};if($parent){$parent.Clauses[0].Item1.Extent.Text}})
$direct=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.Extent.Text -match '(?i)\bcertutil(?:[.]exe)?\b'},$true))
[pscustomobject]@{processStarts=$starts.Count;guards=$conditions;directCertutilCommands=$direct.Count}|ConvertTo-Json -Compress
"""

def write(path,value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    os.replace(temporary,path)

class Checks:
    def __init__(self,home):
        self.home=home;self.directory=home/'certificate-prepare-tests'/dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        self.directory.mkdir(parents=True);self.results=[];self.commands=[];self.store_checks=[];self.synthetic=[]
        self.original=HELPER.read_bytes();self.before=self.snapshot('root-before')
    def run_command(self,name,arguments,expected=0):
        completed=subprocess.run(['pwsh','-NoProfile','-NonInteractive',*arguments],capture_output=True,timeout=30,
                                 env={**os.environ,'STAGE1_CERT_HELPER':str(HELPER)})
        (self.directory/(name+'.log')).write_bytes(completed.stdout+completed.stderr)
        (self.directory/(name+'.exit')).write_text(str(completed.returncode),encoding='utf-8')
        self.commands.append({'name':name,'command':['pwsh','-NoProfile','-NonInteractive',*arguments],'expectedExitCode':expected,'actualExitCode':completed.returncode})
        assert completed.returncode==expected,name+' unexpected native exit; see preserved log'
        return completed.stdout.decode('utf-8-sig')
    def snapshot(self,name):
        return json.loads(self.run_command(name,['-Command',STORE]))
    def absent(self,certificate,inventory):
        thumb=certificate.fingerprint(hashes.SHA1()).hex().upper()
        assert sum(item['thumbprint']==thumb for item in inventory)==0,'Synthetic CA must not exist in Root'
        return thumb
    def fixture(self,offset,wrong_run=False,wrong_cn=False):
        name=(dt.datetime.now()+dt.timedelta(seconds=offset)).strftime('stage1-%Y%m%d-%H%M%S')
        home=self.directory/name;home.mkdir();(home/'private').mkdir()
        write(home/'environment.json',{'run':'wrong-run' if wrong_run else name,'testOnly':True,'kind':'certificate_prepare_contract','noServices':True})
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,('wrong-run' if wrong_cn else name)+' local fixture CA')]);now=dt.datetime.now(dt.timezone.utc)
        ca=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-dt.timedelta(minutes=5)).not_valid_after(now+dt.timedelta(days=30))
            .add_extension(x509.BasicConstraints(ca=True,path_length=0),True)
            .add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),True).sign(key,hashes.SHA256()))
        (home/'private/ca.crt').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
        # No private key, credential, DB, Redis, compose file or service is created.
        thumb=self.absent(ca,self.before);self.synthetic.append({'runDir':str(home),'thumbprint':thumb,'initialExactThumbCount':0});return home,ca
    def action(self,name,home,action,expected=0):
        assert action in ('Prepare','Remove'),'Install is forbidden in this test entry'
        self.run_command(name,['-File',str(HELPER),'-RunDir',str(home),'-Action',action],expected)
    def unchanged(self,name,certificate):
        current=self.snapshot(name);thumb=certificate.fingerprint(hashes.SHA1()).hex().upper();count=sum(item['thumbprint']==thumb for item in current)
        self.store_checks.append({'name':name,'existingCertificateIdentitiesUnchanged':current==self.before,'exactSyntheticThumbCount':count})
        assert current==self.before,'Existing CurrentUser Root certificate identities changed'
        return self.absent(certificate,current)
    def check(self,name,callback):
        try:
            callback();self.results.append({'name':name,'status':'PASS'});print('PASS '+name,flush=True)
        except Exception as failure:
            self.results.append({'name':name,'status':'FAIL','error':str(failure)});raise
        finally:self.report()
    def report(self):
        write(self.directory/'summary.json',{'tests':self.results,'passed':sum(t['status']=='PASS' for t in self.results),'failed':sum(t['status']=='FAIL' for t in self.results),'skipped':0,'commands':self.commands,'existingRootCertificates':len(self.before),'helperSha256':hashlib.sha256(self.original).hexdigest(),'InstallExecuted':False,'storeChecks':self.store_checks,'syntheticCertificates':self.synthetic,'RootCertificateAdded':False if len(self.store_checks)==5 and all(item['existingCertificateIdentitiesUnchanged'] and item['exactSyntheticThumbCount']==0 for item in self.store_checks) else None,'privateKeysOrCredentialsWritten':False})
    def run(self):
        def ast_guard():
            parsed=json.loads(self.run_command('helper-native-ast',['-Command',AST]));assert parsed=={'processStarts':2,'guards':["$present.Count -eq 0 -and $Action -eq 'Install'",'$present.Count -eq 1'],'directCertutilCommands':0}
        self.check('native_ast_import_is_guarded_by_Install_only',ast_guard)
        valid,ca=self.fixture(0)
        def prepare():
            self.action('prepare-absent',valid,'Prepare');record=json.loads((valid/'fixture-certificate-trust.json').read_text(encoding='utf-8-sig'))
            assert record['state']=='Installing' and record['importedByThisRun'] and not record['existedBefore']
            assert record['thumbprint']==self.unchanged('root-after-prepare',ca)
            assert record['caFileSha256']==hashlib.sha256((valid/'private/ca.crt').read_bytes()).hexdigest().upper()
            assert record['certificateSha256']==ca.fingerprint(hashes.SHA256()).hex().upper()
        self.check('real_Prepare_records_Installing_without_import_or_store_change',prepare)
        def remove_absent():
            self.absent(ca,self.snapshot('root-before-absent-remove'));self.action('remove-absent',valid,'Remove')
            assert json.loads((valid/'fixture-certificate-trust.json').read_text(encoding='utf-8-sig'))['state']=='Removed'
            self.unchanged('root-after-absent-remove',ca)
        self.check('real_Remove_of_absent_synthetic_CA_does_not_change_store',remove_absent)
        for offset,name,options in [(1,'wrong_run',{'wrong_run':True}),(2,'wrong_CN',{'wrong_cn':True})]:
            fixture,cert=self.fixture(offset,**options)
            def rejected(fixture=fixture,cert=cert,name=name):
                self.action(name+'-rejected',fixture,'Prepare',1);assert not (fixture/'fixture-certificate-trust.json').exists();self.unchanged('root-after-'+name,cert)
            self.check('real_Prepare_rejects_'+name,rejected)
        def bad_hash():
            fixture,cert=self.fixture(3);self.action('hash-primer',fixture,'Prepare')
            path=fixture/'fixture-certificate-trust.json';original=path.read_bytes();(fixture/'fixture-certificate-trust.json.before-invalid-hash').write_bytes(original)
            record=json.loads(original.decode('utf-8-sig'));record['caFileSha256']='0'*64;assert path.read_bytes()==original;write(path,record)
            self.action('invalid_hash-rejected',fixture,'Prepare',1);self.unchanged('root-after-invalid-hash',cert)
        self.check('real_Prepare_rejects_fingerprint_record_hash_mismatch',bad_hash)
        assert HELPER.read_bytes()==self.original,'Helper changed concurrently; do not validate a stale version'
        write(self.directory/'exit.json',{'exitCode':0});print('Certificate Prepare checks: 6 passed, 0 failed, 0 skipped; '+str(self.directory))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-dir',type=Path,required=True)
    args=parser.parse_args();home=args.run_dir.resolve()
    if ROOT/'reports' not in home.parents or not home.name.startswith('stage1-'):raise SystemExit('Explicit owned stage1 report directory required')
    checks=Checks(home)
    try:checks.run()
    except Exception as failure:
        checks.report();write(checks.directory/'exit.json',{'exitCode':1});print('FAIL '+str(failure)+'; '+str(checks.directory),file=sys.stderr);sys.exit(1)
