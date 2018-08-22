#!/usr/bin/env python3

from sys import argv
from os import makedirs
import urllib.request
import shutil

"""
Helper script for downloading dependencies from Maven Central.

To use, first cd into a local repo, then pass in a "Maven Coordinate" for the dependency.

Example:
cd Android/gradle-build-repo
../../scripts/maven_fetch.py com.sun.istack:istack-commons:2.21
"""

coordinates = argv[1]

groupId, artifactId, version = coordinates.split(':')

path = groupId.replace('.', '/') + '/' + artifactId + '/' + version
makedirs(path)
name = artifactId + '-' + version
repo = "https://repo1.maven.org/maven2/"
for filetype in ['pom', 'jar']:
  filepath = path + '/' + name + '.' + filetype
  url = repo + filepath

  print(url)
  with urllib.request.urlopen(url) as response, open(filepath, 'wb') as outfile:
    shutil.copyfileobj(response, outfile)
