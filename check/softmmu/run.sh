#!/bin/zsh
set -euo pipefail
root=/home/yang/work/AnserCore
work=/tmp/softmmu-check
firtool=${FIRTOOL:-/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool}
rm -rf "$work"
mkdir -p "$work/sv"
find "$root/ansercore/src" -name '*.scala' -not -path '*/soc/*' | sort > "$work/srcs.txt"
cd "$work"
SOFTMMU_OUT="$work/sv" FIRTOOL="$firtool" \
  scala-cli --power run --offline --server=false --main-class softmmucheck.EmitSoftMmu \
  --scala 2.13.18 \
  --dep org.chipsalliance::chisel:7.16.0 \
  --plugin org.chipsalliance:::chisel-plugin:7.16.0 \
  --scalac-option -Xcheckinit \
  "$root/check/softmmu/EmitSoftMmu.scala" $(cat srcs.txt)
CCACHE_DISABLE=1 verilator --cc --exe --build -j 4 \
  --top-module SoftMmuProbe \
  -Mdir "$work/obj" \
  -CFLAGS "-std=c++17 -I/home/yang/work/sim_verilator/csrc" \
  "$work"/sv/*.sv \
  "$root/check/softmmu/check.cpp" \
  /home/yang/work/sim_verilator/csrc/service/mmu/mmu.cpp
"$work/obj/VSoftMmuProbe"
