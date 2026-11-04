#!/bin/zsh
set -euo pipefail
root=/home/yang/work/AnserCore
work=/tmp/softbackend-check
firtool=${FIRTOOL:-/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool}
rm -rf "$work"
mkdir -p "$work/sv"
find "$root/ansercore/src" -name '*.scala' -not -path '*/soc/*' | sort > "$work/srcs.txt"
cd "$work"
SOFTBACKEND_OUT="$work/sv" FIRTOOL="$firtool" \
  scala-cli --power run --offline --server=false --main-class softbackendcheck.EmitSoftBackend \
  --scala 2.13.18 \
  --dep org.chipsalliance::chisel:7.16.0 \
  --plugin org.chipsalliance:::chisel-plugin:7.16.0 \
  --scalac-option -Xcheckinit \
  "$root/check/softbackend/EmitSoftBackend.scala" $(cat srcs.txt)
CCACHE_DISABLE=1 verilator --cc --exe --build -j 4 \
  --top-module SoftBackendProbe \
  -Mdir "$work/obj" \
  -CFLAGS "-std=c++17 -I/home/yang/work/sim_verilator/csrc" \
  "$work"/sv/*.sv \
  "$root/check/softbackend/check.cpp" \
  /home/yang/work/sim_verilator/csrc/service/ideal/ideal.cpp \
  /home/yang/work/sim_verilator/csrc/service/ideal/exec.cpp \
  /home/yang/work/sim_verilator/csrc/service/ideal/decode.cpp
"$work/obj/VSoftBackendProbe"
