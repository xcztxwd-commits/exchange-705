"""Revoke a new fixture-only old DB identity, including its live connection; never touch inherited identities."""
from rehearse import *

def main():
    import pymysql
    source=db('source','mt705_s4_files');name='s4old_'+secrets.token_hex(6);password=secrets.token_hex(32);connection=None
    account="'"+name+"'";secret="'"+password+"'" # Only generated lowercase hex, never user input.
    before=controlled.all_fields(source)
    source.sql('CREATE USER '+account+"@'%' IDENTIFIED BY "+secret+';GRANT SELECT ON '+core.ident(source.database)+'.* TO '+account+"@'%';")
    try:
        connection=pymysql.connect(host='127.0.0.1',port=source._port,user=name,password=password,database=source.database,autocommit=True)
        with connection.cursor() as c:c.execute('SELECT 1');assert c.fetchone()==(1,)
        thread=connection.thread_id();assert source.query('SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID='+str(thread)+' AND USER='+core.literal(name))==['1']
        source.sql('KILL CONNECTION '+str(thread)+';DROP USER '+account+"@'%';")
        try:
            with connection.cursor() as c:c.execute('SELECT 1')
        except pymysql.MySQLError:pass
        else:raise AssertionError('Old established session remains usable')
        try:pymysql.connect(host='127.0.0.1',port=source._port,user=name,password=password,database=source.database)
        except pymysql.err.OperationalError as e:assert e.args[0]==1045
        else:raise AssertionError('Revoked old identity reauthenticated')
        assert controlled.all_fields(source)==before
        save(OUT/'old-identity.json',{'status':'PASS_FRESH','checks':['only newly-created isolated DB identity exercised','old live connection killed after exact ID/user match','revoked identity cannot reconnect: MySQL 1045','all application data bytes unchanged'],'count':4,'scope':'fixture-only DB credentials; not production credential rotation or application token revocation','productionCredentialsTouched':False})
    finally:
        if connection:connection.close()
        source.sql('DROP USER IF EXISTS '+account+"@'%';")
    print('OLD_FIXTURE_IDENTITY_DENIED; application rows unchanged')

if __name__=='__main__':main()
