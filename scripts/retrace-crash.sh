#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: sh scripts/retrace-crash.sh <mapping.txt> <stacktrace.txt>" >&2
  exit 2
fi

mapping_file=$1
stacktrace_file=$2

if [ ! -f "$mapping_file" ] || [ ! -f "$stacktrace_file" ]; then
  echo "Mapping and stacktrace files must both exist" >&2
  exit 2
fi

if ! command -v retrace >/dev/null 2>&1; then
  echo "The Android R8 'retrace' command is not on PATH" >&2
  exit 127
fi

retrace "$mapping_file" "$stacktrace_file"
