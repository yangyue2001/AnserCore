#!/bin/zsh
# Boot the fixed OpenSBI/Linux payload on the SoC.
# UART bytes written by the ideal backend go to stdout.
# SOCDIFF_COMMITS=0 runs until --max-cycles. RAM is 256 MiB, matching the payload.
set -euo pipefail
root=/home/yang/work/AnserCore
sim=/home/yang/work/sim_verilator
work=${SOC_LINUX_WORK:-/tmp/soc-linux}
image=${1:-/home/yang/work/ISS/build/linux/artifacts/fw_payload.bin}
cycles=${MAX_CYCLES:-2000000}
firtool=${FIRTOOL:-/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool}
test -f "$image"
if [[ "${REBUILD:-1}" == 1 || ! -x "$work/simbuild/sim-off" ]]; then
  rm -rf "$work/sv" "$work/simbuild" "$work/build"
  mkdir -p "$work/sv"
  find "$root/ansercore/src" -name '*.scala' | sort > "$work/srcs.txt"
  find "$sim/scala/src/main/scala" -name '*.scala' | sort > "$work/diffsrcs.txt"
  (
    cd "$work"
    SOCDIFF_OUT="$work/sv" SOCDIFF_RAM_MB=256 SOCDIFF_COMMITS=0 FIRTOOL="$firtool" \
      scala-cli --power run --offline --server=false --main-class socdiff.EmitSocDiff \
      --scala 2.13.18 \
      --dep org.chipsalliance::chisel:7.16.0 \
      --plugin org.chipsalliance:::chisel-plugin:7.16.0 \
      --scalac-option -language:reflectiveCalls \
      --scalac-option -Xcheckinit \
      "$root/check/socdiff/EmitSocDiff.scala" $(cat "$work/srcs.txt") $(cat "$work/diffsrcs.txt")
  )
  test -s "$work/build/generated-src/probes.cpp"
  sv=$(find "$work/sv" -name '*.sv' | sort | tr '\n' ' ')
  sf=/home/yang/work/ISS/third_party/softfloat
  sfobj="$work/softfloat-obj"
  rm -rf "$sfobj"
  mkdir -p "$sfobj"
  (
    cd "$sfobj"
    gcc -O2 -I"$sf" -c "$sf"/*.c
    ar rcs "$work/libsoftfloat.a" ./*.o
  )
  export CC=/usr/bin/gcc
  export CXX=/usr/bin/g++
  export CCACHE_DISABLE=1
  export CCACHE_DIR=/tmp/ccache-soc
  export CCACHE_TEMPDIR=/tmp/ccache-soc/tmp
  export TMPDIR=/tmp
  mkdir -p "$CCACHE_TEMPDIR"
  make -C "$sim" smoke EMU_TRACE=0 TOP=SimTop JOBS="$(nproc)" \
    BUILD_DIR="$work/simbuild" \
    RTL="$sv $work/build/generated-src/probes.cpp $root/check/socdiff/force_dpi.cpp" \
    VERILATOR_EXTRA="-O1 -Wno-UNUSED -Wno-UNOPTFLAT -Wno-CASEINCOMPLETE -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC -LDFLAGS $work/libsoftfloat.a"
fi
log=${SOC_LINUX_LOG:-$work/linux.log}
"$work/simbuild/sim-off" \
  --image="$image" \
  --ram-size=256MB \
  --max-cycles="$cycles" \
  --diff-timeout=0 \
  --seed=0 | tee "$log"
