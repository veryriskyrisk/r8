#!/usr/bin/env python
# Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

'''
Run R8 (with the class-file backend) to optimize a command-line program.

Given an input JAR (default: r8.jar) and a main-class, generates a new input JAR
with the given main-class in the manifest along with a -keep rule for keeping
just the main entrypoint, and runs R8 in release+classfile mode on the JAR.
'''

import argparse
import os
import re
import sys
import toolhelper
import utils
import zipfile

KEEP = '-keep public class %s { public static void main(...); }\n'
MANIFEST_PATH = 'META-INF/MANIFEST.MF'
MANIFEST = 'Manifest-Version: 1.0\nMain-Class: %s\n\n'
MANIFEST_PATTERN = r'Main-Class:\s*(\S+)'
RT = os.path.join(utils.REPO_ROOT, 'third_party/openjdk/openjdk-rt-1.8/rt.jar')

parser = argparse.ArgumentParser(description=__doc__.strip(),
                                 formatter_class=argparse.RawTextHelpFormatter)
parser.add_argument(
    '-i', '--input-jar',
    help='Input JAR to use (default: build/libs/r8.jar)')
parser.add_argument(
    '-o', '--output-jar',
    help='Path to output JAR (default: build/libs/<MainClass>-min.jar)')
parser.add_argument(
    '-l', '--lib',
    help='Path to rt.jar to use instead of OpenJDK 1.8')
parser.add_argument(
    '-m', '--mainclass',
    help='Create/overwrite MANIFEST.MF with the given Main-Class')

def generate_output_name(input_jar, mainclass):
  if not mainclass:
    input_base, input_ext = os.path.splitext(input_jar)
    return '%s-min%s' % (input_base, input_ext)
  base = mainclass[mainclass.rindex('.')+1:] if '.' in mainclass else mainclass
  return os.path.join(utils.LIBS, '%s-min.jar' % base)

def repackage(input_jar, output_jar, mainclass):
  print("Repackaging %s to %s with Main-Class: %s..." %
        (input_jar, output_jar, mainclass))
  manifest = MANIFEST % mainclass
  with zipfile.ZipFile(input_jar, 'r') as input_zf:
    with zipfile.ZipFile(output_jar, 'w') as output_zf:
      for zipinfo in input_zf.infolist():
        if zipinfo.filename.upper() == MANIFEST_PATH:
          assert manifest is not None
          output_zf.writestr(MANIFEST_PATH, manifest)
          manifest = None
        else:
          output_zf.writestr(zipinfo, input_zf.read(zipinfo))
      if manifest is not None:
        output_zf.writestr(MANIFEST_PATH, manifest)

def extract_mainclass(input_jar):
  with zipfile.ZipFile(input_jar, 'r') as input_zf:
    try:
      manifest = input_zf.getinfo(MANIFEST_PATH)
    except KeyError:
      raise SystemExit('No --mainclass specified and no manifest in input JAR.')
    mo = re.search(MANIFEST_PATTERN, input_zf.read(manifest))
    if not mo:
      raise SystemExit(
          'No --mainclass specified and no Main-Class in input JAR manifest.')
    return mo.group(1)

def main():
  args = parser.parse_args()
  mainclass = args.mainclass
  input_jar = args.input_jar or utils.R8_JAR
  output_jar = args.output_jar or generate_output_name(input_jar, mainclass)
  lib = args.lib or RT
  with utils.TempDir() as path:
    if mainclass:
      tmp_input_path = os.path.join(path, 'input.jar')
      repackage(input_jar, tmp_input_path, mainclass)
    else:
      tmp_input_path = input_jar
      mainclass = extract_mainclass(input_jar)
    keep_path = os.path.join(path, 'keep.txt')
    with open(keep_path, 'w') as fp:
      fp.write(KEEP % mainclass)
    args = ('--lib', lib,
            '--classfile',
            '--output', output_jar,
            '--pg-conf', keep_path,
            '--release',
            tmp_input_path)
    return toolhelper.run('r8', args)

if __name__ == '__main__':
  sys.exit(main())
