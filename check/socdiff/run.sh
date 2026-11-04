#!/bin/zsh
set -euo pipefail
root=/home/yang/work/AnserCore
sim=/home/yang/work/sim_verilator
work=/tmp/socdiff-check
firtool=${FIRTOOL:-/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool}
rm -rf "$work"
mkdir -p "$work/sv" "$work/prog"
python3 - << 'PY'
import pathlib, struct
insts = [0x00100093, 0x00200113, 0x00408193, 0x00310213]
insts += [0x00108093] * 28
path = pathlib.Path("/tmp/socdiff-check/prog/addi.bin")
path.write_bytes(b"".join(struct.pack("<I", inst) for inst in insts))
print(f"image {path} {len(insts)} instructions")
PY
find "$root/ansercore/src" -name '*.scala' | sort > "$work/srcs.txt"
find "$sim/scala/src/main/scala" -name '*.scala' | sort > "$work/diffsrcs.txt"
cd "$work"
SOCDIFF_OUT="$work/sv" FIRTOOL="$firtool" \
  scala-cli --power run --offline --server=false --main-class socdiff.EmitSocDiff \
  --scala 2.13.18 \
  --dep org.chipsalliance::chisel:7.16.0 \
  --plugin org.chipsalliance:::chisel-plugin:7.16.0 \
  --scalac-option -language:reflectiveCalls \
  --scalac-option -Xcheckinit \
  "$root/check/socdiff/EmitSocDiff.scala" $(cat "$work/srcs.txt") $(cat "$work/diffsrcs.txt")
test -s "$work/build/generated-src/probes.cpp"
sv=$(find "$work/sv" -name '*.sv' | sort | tr '\n' ' ')
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
  VERILATOR_EXTRA="-O1 -Wno-UNUSED -Wno-UNOPTFLAT -Wno-CASEINCOMPLETE -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC"
"$work/simbuild/sim-off" \
  --image="$work/prog/addi.bin" \
  --diff=/home/yang/work/ISS/build/librviss_ref.so \
  --ram-size=128MB \
  --max-cycles=50000 \
  --diff-timeout=0 \
  --seed=0 | tee "$work/sim.log"
python3 - << 'PY'
import pathlib, re, sys
log = pathlib.Path("/tmp/socdiff-check/sim.log").read_text(errors="replace")
if "difftest mismatch" in log or "difftest timeout" in log:
    sys.exit("difftest failed")
match = re.search(r"difftest checked (\d+) commits", log)
if match is None or int(match.group(1)) < 4:
    sys.exit(f"not enough commits: {match.group(1) if match else 'none'}")
print("SOC_DIFF_OK", match.group(1))
PY
