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

infile = gzip.open(sys.argv[1])
out_prefix = sys.argv[2]

v4file = open(out_prefix + '.v4', 'wb')
v6file = open(out_prefix + '.v6', 'wb')

r = csv.reader(infile)
for start, end, country in r:
  a = ipaddress.ip_address(unicode(start))
  f = v4file if a.version == 4 else v6file
  f.write(a.packed)
  f.write(country)

infile.close()
v4file.close()
v6file.close()

