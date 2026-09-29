"""Disposable MySQL 5.7 regressions; no host ports, production data or shared volumes."""
import json
import pathlib
import re
import subprocess
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / 'exchange-backend/src/main/resources/db/migration/widen_admin_menu_code.sql'


def main():
    name = 'menu-startup-test-' + uuid.uuid4().hex
    password = uuid.uuid4().hex
    compose = (ROOT / 'compose.demo.yaml').read_text(encoding='utf-8')
    section = compose.split('  demo-mysql:\n', 1)[1].split('  demo-redis:', 1)[0]
    command = json.loads(re.search(r'^    command: (.+)$', section, re.M).group(1))
    assert command == ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci']
    subprocess.run(['docker', 'run', '-d', '--name', name, '--network', 'none', '--tmpfs', '/var/lib/mysql',
                    '-e', 'MYSQL_ROOT_PASSWORD=' + password, '-e', 'MYSQL_DATABASE=release_test',
                    'mysql:5.7', *command], check=True, capture_output=True)
    try:
        def sql(statement, check=True):
            result = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + password, name,
                                     'mysql', '-uroot', '--default-character-set=utf8mb4', '-NB', 'release_test'],
                                    input=statement.encode('utf-8'), capture_output=True)
            if check and result.returncode:
                raise AssertionError(result.stderr.decode('utf-8'))
            return result.stdout.decode('utf-8').strip(), result.returncode

        for _ in range(90):
            if sql('SELECT 1;', check=False)[1] == 0:
                break
            time.sleep(2)
        else:
            raise AssertionError('Disposable database did not become ready')
        migration = MIGRATION.read_text(encoding='utf-8')
        sql(migration)  # Fresh schema: safe no-op before JPA creates tables.
        assert sql('SELECT @@character_set_server,@@collation_server;')[0] == 'utf8mb4\tutf8mb4_unicode_ci'
        sql("CREATE TABLE admin_menu(id BIGINT PRIMARY KEY,menu_name VARCHAR(50) NOT NULL,menu_code VARCHAR(150) NOT NULL UNIQUE);"
            "INSERT INTO admin_menu VALUES(1,'权限菜单',REPEAT('a',120));")
        sql(migration)
        assert sql('SELECT menu_name,LENGTH(menu_code) FROM admin_menu;')[0] == '权限菜单\t120'
        sql('DROP TABLE admin_menu;')
        for charset in ('utf8mb4', 'latin1'):
            sql(f"CREATE TABLE admin_menu(id BIGINT PRIMARY KEY,menu_name VARCHAR(50) NOT NULL,menu_code VARCHAR(50) NOT NULL UNIQUE COMMENT 'permission') DEFAULT CHARSET={charset};"
                "INSERT INTO admin_menu VALUES(1,'existing','existing');")
            assert sql("INSERT INTO admin_menu VALUES(2,'long',REPEAT('b',120));", check=False)[1] != 0
            sql(migration)
            sql(migration)  # Idempotency.
            assert sql("SELECT CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_menu' AND COLUMN_NAME='menu_code';")[0] == f'150\t{charset}\tpermission'
            assert sql('SELECT * FROM admin_menu;')[0] == '1\texisting\texisting'
            sql("INSERT INTO admin_menu VALUES(2,'long',REPEAT('b',120));")
            assert sql("INSERT INTO admin_menu VALUES(3,'duplicate','existing');", check=False)[1] != 0
            sql('ALTER TABLE admin_menu MODIFY menu_code VARCHAR(200) NOT NULL;')
            sql(migration)
            assert sql("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_menu' AND COLUMN_NAME='menu_code';")[0] == '200'
            sql('DROP TABLE admin_menu;')
        sql('CREATE TABLE admin_menu(menu_code VARCHAR(50) NULL);')
        assert sql(migration, check=False)[1] != 0  # Unexpected schema fails closed.
        print('PASS: fresh UTF-8 Chinese/long seeds; old utf8mb4/latin1 upgrade; repeat migration; rows, charset, comment and uniqueness preserved; no shrinking; unexpected schema rejected.')
    finally:
        subprocess.run(['docker', 'rm', '-f', name], check=True, capture_output=True)


if __name__ == '__main__':
    main()
