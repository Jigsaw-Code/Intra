#!/usr/bin/env python3
import csv
import gzip
import ipaddress
import sys

"""
Usage:
Download the latest IP-to-country database from https://db-ip.com/db/download/country
$ python dbip_shrink.py dbip-country-[date].csv.gz dbip
Writes a packed binary file suitable for bisection search.
Thanks to DB-IP.com for offering a suitable database under a CC-BY license.
"""

out_prefix = sys.argv[2]

with open(out_prefix + '.v4', 'wb') as v4file, \
        open(out_prefix + '.v6', 'wb') as v6file, \
        gzip.open(sys.argv[1], mode='rt') as infile:

    for start, end, country in csv.reader(infile):
        addr = ipaddress.ip_address(start)
        file = v4file if addr.version == 4 else v6file
        file.write(addr.packed)
        file.write(bytes(country, 'us-ascii'))
        
