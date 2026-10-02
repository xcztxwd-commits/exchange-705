"""Subnet-selection checks stop before TLS/services; no Docker resource is changed."""
import ipaddress
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import environment

class PreparedCompose(Exception):pass

class SubnetAllocationTest(unittest.TestCase):
    def prepare(self,subnets,fail_inspect=False):
        saved={};commands=[]
        inventory=[{'IPAM':{'Config':[{'Subnet':value} for value in subnets]}}]
        def docker(command):
            commands.append(command)
            if command==['docker','network','ls','--quiet']:
                return subprocess.CompletedProcess(command,0,stdout=b'fixture-network-id\n')
            self.assertEqual(['docker','network','inspect','fixture-network-id'],command)
            if fail_inspect:raise RuntimeError('Docker inventory failed')
            return subprocess.CompletedProcess(command,0,stdout=json.dumps(inventory).encode())
        def save(path,value):
            saved[path.name]=value
            if path.name=='compose.json':raise PreparedCompose()
        with tempfile.TemporaryDirectory(prefix='stage1-network-unit-') as temp:
            home=Path(temp)/'stage1-fixture';home.mkdir()
            with patch.object(environment.migration,'run',side_effect=docker), \
                 patch.object(environment.migration,'restrict_directory',side_effect=lambda path:path.mkdir()), \
                 patch.object(environment,'save',side_effect=save):
                try:environment.prepare(home)
                except PreparedCompose:pass
        self.assertEqual(2,len(commands))
        self.assertNotIn('rm',sum(commands,[]))
        subnet=saved['environment.json']['networkSubnet']
        self.assertEqual(subnet,saved['compose.json']['networks']['default']['ipam']['config'][0]['subnet'])
        for other in subnets:
            network=ipaddress.ip_network(other)
            if network.version==4:self.assertFalse(ipaddress.ip_network(subnet).overlaps(network))
        return subnet

    def test_empty_network_and_old_run_do_not_reuse_old_subnet(self):
        self.assertEqual('10.236.0.0/24',self.prepare(['10.236.84.0/24']))
    def test_broad_existing_ranges_block_every_overlapping_candidate(self):
        self.assertEqual('10.237.0.0/24',self.prepare(['10.236.0.0/16','fd00::/64']))
    def test_partial_ranges_are_not_compared_as_exact_strings(self):
        self.assertEqual('10.236.2.0/24',self.prepare(['10.236.0.0/23']))
    def test_pool_exhaustion_refuses_without_removing_old_networks(self):
        with self.assertRaisesRegex(RuntimeError,'No unused dedicated Docker subnet'):self.prepare(['10.236.0.0/14'])
    def test_failed_inventory_never_assumes_network_is_free(self):
        with self.assertRaisesRegex(RuntimeError,'Docker inventory failed'):self.prepare([],True)

if __name__=='__main__':unittest.main()
