package device.axi

import chisel3._
import chisel3.util._
import utils.Mux1H

import scala.math.Ordering

// Diplomacy-free AXI4 crossbar.
// Behavior follows rocket-chip amba/axi4/Xbar.scala:
//   address routing uses the same minimal bit mask as AddressDecoder
//   AW destination is recorded in a flow queue so W does not depend on aw.ready
//   one ID may have up to maxFlightPerId requests, and only while they target one slave
//   output IDs are packed into per-master power-of-two blocks
// An address that matches no slave stalls. It is not acknowledged.

case class AXI4AddrSet(base: BigInt, mask: BigInt) extends Ordered[AXI4AddrSet] {
  require(base >= 0, s"negative address base: $base")
  require((base & mask) == 0, s"misaligned address set base=$base mask=$mask")

  def contains(addr: BigInt): Boolean = ((addr ^ base) & ~mask) == 0

  def contains(addr: UInt): Bool = {
    val width = addr.getWidth
    // BigInt ~mask is negative. Keep only the bits inside addr.
    val check = (~mask) & ((BigInt(1) << width) - 1)
    ((addr ^ base.U(width.W)) & check.U(width.W)) === 0.U
  }

  def overlaps(that: AXI4AddrSet): Boolean =
    (~(this.mask | that.mask) & (this.base ^ that.base)) == 0

  def widen(ignored: BigInt): AXI4AddrSet =
    AXI4AddrSet(base & ~ignored, mask | ignored)

  def intersect(that: AXI4AddrSet): Option[AXI4AddrSet] =
    if (!overlaps(that)) None
    else Some(AXI4AddrSet(base | that.base, mask & that.mask))

  def subtract(that: AXI4AddrSet): Seq[AXI4AddrSet] =
    intersect(that) match {
      case None => Seq(this)
      case Some(remove) =>
        AXI4AddrSet.enumerateBits(mask & ~remove.mask).map { bit =>
          val nextMask = (mask & (bit - 1)) | remove.mask
          val nextBase = (remove.base ^ bit) & ~nextMask
          AXI4AddrSet(nextBase, nextMask)
        }
    }

  def compare(that: AXI4AddrSet): Int = {
    val primary = this.base.compare(that.base)
    if (primary != 0) primary else that.mask.compare(this.mask)
  }

  override def toString: String =
    if (mask >= 0) f"AXI4AddrSet(0x$base%x, 0x$mask%x)"
    else f"AXI4AddrSet(0x$base%x, ~0x${~mask}%x)"
}

object AXI4AddrSet {
  def enumerateBits(mask: BigInt): Seq[BigInt] = {
    def helper(left: BigInt): Seq[BigInt] =
      if (left == 0) Nil
      else {
        val bit = left & -left
        bit +: helper(left & ~bit)
      }
    helper(mask)
  }

  def unify(sets: Seq[AXI4AddrSet], bit: BigInt): Seq[AXI4AddrSet] =
    sets.distinct.groupBy(set => set.copy(base = set.base & ~bit)).map { case (key, group) =>
      if (group.size == 1) group.head else key.copy(mask = key.mask | bit)
    }.toSeq

  def unify(sets: Seq[AXI4AddrSet]): Seq[AXI4AddrSet] = {
    val bits = sets.map(_.base).foldLeft(BigInt(0))(_ | _)
    enumerateBits(bits).foldLeft(sets) { case (acc, bit) => unify(acc, bit) }.sorted
  }
}

// Same greedy bit search as freechips.rocketchip.diplomacy.AddressDecoder.
private object AXI4AddrDecoder {
  private val setOrder = Ordering.ordered[AXI4AddrSet]
  private val portOrder = Ordering.Iterable(setOrder)
  private val partitionOrder = Ordering.Iterable(portOrder)

  def apply(ports: Seq[Seq[AXI4AddrSet]]): BigInt = {
    val live = ports.filter(_.nonEmpty)
    if (live.size <= 1) {
      BigInt(0)
    } else {
      val maxBase = live.map(_.map(_.base).max).max
      val maxBits = maxBase.bitLength
      val initial = Seq(live.map(_.sorted).sorted(portOrder))
      val selected = recurse(initial, (0 until maxBits).map(i => BigInt(1) << i).reverse)
      if (selected.isEmpty) BigInt(0) else selected.reduceLeft(_ | _)
    }
  }

  private def score(parts: Seq[Seq[Seq[AXI4AddrSet]]]): Seq[Int] = {
    val maxPorts = parts.map(_.size).max
    val maxSets = parts.map(_.map(_.size).sum).max
    val squarePorts = parts.map(part => part.size * part.size).sum
    val squareSets = parts.map(_.map(port => port.size * port.size).sum).max
    Seq(maxPorts, maxSets, squarePorts, squareSets)
  }

  private def splitPort(port: Seq[AXI4AddrSet], bit: BigInt): (Seq[AXI4AddrSet], Seq[AXI4AddrSet]) = {
    val low = AXI4AddrSet(0, ~bit)
    val high = AXI4AddrSet(bit, ~bit)
    (port.filter(_.overlaps(low)), port.filter(_.overlaps(high)))
  }

  private def splitPorts(ports: Seq[Seq[AXI4AddrSet]], bit: BigInt): (Seq[Seq[AXI4AddrSet]], Seq[Seq[AXI4AddrSet]]) = {
    val split = ports.map(splitPort(_, bit))
    val low = split.map(_._1).filter(_.nonEmpty).sorted(portOrder)
    val high = split.map(_._2).filter(_.nonEmpty).sorted(portOrder)
    (low, high)
  }

  private def splitAll(parts: Seq[Seq[Seq[AXI4AddrSet]]], bit: BigInt): Seq[Seq[Seq[AXI4AddrSet]]] = {
    val split = parts.map(splitPorts(_, bit))
    val next = (split.map(_._1).filter(_.nonEmpty) ++ split.map(_._2).filter(_.nonEmpty)).sorted(partitionOrder)
    val kept = next.init.zip(next.tail).collect { case (left, right) if partitionOrder.compare(left, right) != 0 => right }
    next.head +: kept
  }

  private def recurse(parts: Seq[Seq[Seq[AXI4AddrSet]]], bits: Seq[BigInt]): Seq[BigInt] =
    if (parts.forall(_.size <= 1)) {
      Nil
    } else {
      val candidates = bits.map { bit =>
        val next = splitAll(parts, bit)
        (score(next), bit, next)
      }
      val (_, bestBit, bestParts) = candidates.min(Ordering.by[(Seq[Int], BigInt, Seq[Seq[Seq[AXI4AddrSet]]]), Iterable[Int]](_._1))
      bestBit +: recurse(bestParts, bits.filter(_ != bestBit))
    }
}

object AXI4Xbar {
  // (start, end) half-open ID block for each master. Last master gets the low IDs.
  def assignRanges(sizes: Seq[Int]): Seq[(Int, Int)] = {
    val pow2 = sizes.map(size => if (size == 0) 0 else 1 << log2Ceil(size))
    val ordered = pow2.zipWithIndex.sortBy(_._1)
    val starts = ordered.scanRight(0) { case ((size, _), acc) => size + acc }.tail
    val placed = ordered.zip(starts).map { case ((size, index), start) =>
      val range = if (size == 0) (0, 0) else (start, start + size)
      (range, index)
    }
    placed.sortBy(_._2).map(_._1)
  }

  def outputIdBits(nMasters: Int, idBits: Int): Int = {
    require(nMasters >= 1 && idBits >= 1)
    val end = assignRanges(Seq.fill(nMasters)(1 << idBits)).map(_._2).max
    math.max(1, log2Ceil(end))
  }

  def widenRoutes(slaveAddrs: Seq[Seq[AXI4AddrSet]]): Seq[Seq[AXI4AddrSet]] = {
    require(slaveAddrs.nonEmpty && slaveAddrs.forall(_.nonEmpty), "every AXI slave needs an address set")
    slaveAddrs.combinations(2).foreach { pair =>
      pair(0).foreach { left =>
        pair(1).foreach { right =>
          require(!left.overlaps(right), s"AXI slave addresses overlap: $left $right")
        }
      }
    }
    val mask = AXI4AddrDecoder(slaveAddrs)
    val widened = slaveAddrs.map(sets => AXI4AddrSet.unify(sets.map(_.widen(~mask)).distinct))
    widened.combinations(2).foreach { pair =>
      pair(0).foreach { left =>
        pair(1).foreach { right =>
          require(!left.overlaps(right), s"AXI routes overlap after decoding: $left $right")
        }
      }
    }
    widened
  }

  def owners(routes: Seq[Seq[AXI4AddrSet]], addr: BigInt): Seq[Int] =
    routes.zipWithIndex.collect { case (sets, index) if sets.exists(_.contains(addr)) => index }
}

class AXI4Xbar(
  val nMasters: Int,
  slaveAddrs: Seq[Seq[AXI4AddrSet]],
  params: AXI4Params = AXI4Params(),
  maxFlightPerId: Int = 7,
  awQueueDepth: Int = 2,
) extends Module {
  require(nMasters >= 1)
  require(slaveAddrs.nonEmpty)
  require(maxFlightPerId >= 1)
  require(awQueueDepth >= 1)

  val nSlaves = slaveAddrs.size
  val idRanges = AXI4Xbar.assignRanges(Seq.fill(nMasters)(1 << params.idBits))
  val outIdBits = AXI4Xbar.outputIdBits(nMasters, params.idBits)
  val outParams = params.copy(idBits = outIdBits)
  val routed = AXI4Xbar.widenRoutes(slaveAddrs)
  idRanges.foreach { case (start, end) =>
    require(end - start == (1 << params.idBits), s"master ID block $start:$end is not 2^idBits")
    require((start & ((1 << params.idBits) - 1)) == 0, s"master ID base $start is not aligned")
  }

  val io = IO(new Bundle {
    val in  = Vec(nMasters, new AXI4SlaveIO(params))
    val out = Vec(nSlaves, new AXI4MasterIO(outParams))
  })

  private val endId = 1 << params.idBits
  private val inAr = Wire(Vec(nMasters, Decoupled(new AXI4BundleA(outParams))))
  private val inAw = Wire(Vec(nMasters, Decoupled(new AXI4BundleA(outParams))))
  private val inW = Wire(Vec(nMasters, Decoupled(new AXI4BundleW(params))))
  private val inR = Wire(Vec(nMasters, Decoupled(new AXI4BundleR(outParams))))
  private val inB = Wire(Vec(nMasters, Decoupled(new AXI4BundleB(outParams))))
  private val outAr = Wire(Vec(nSlaves, Decoupled(new AXI4BundleA(outParams))))
  private val outAw = Wire(Vec(nSlaves, Decoupled(new AXI4BundleA(outParams))))
  private val outW = Wire(Vec(nSlaves, Decoupled(new AXI4BundleW(params))))
  private val outR = Wire(Vec(nSlaves, Decoupled(new AXI4BundleR(outParams))))
  private val outB = Wire(Vec(nSlaves, Decoupled(new AXI4BundleB(outParams))))

  private def routeOf(addr: UInt): Seq[Bool] =
    routed.map(sets => sets.map(_.contains(addr)).reduce(_ || _))

  private val arSel = io.in.map(port => routeOf(port.ar.bits.addr))
  private val awSel = io.in.map(port => routeOf(port.aw.bits.addr))

  // W follows the AW destination. The queue is flow-through and does not look at aw.ready,
  // so a slave that does awready := wvalid cannot close a loop.
  private val awIn = Seq.fill(nMasters) {
    if (nSlaves > 1) Some(Module(new Queue(UInt(nSlaves.W), awQueueDepth, flow = true))) else None
  }
  private val awOut = Seq.fill(nSlaves) {
    if (nMasters > 1) Some(Module(new Queue(UInt(nMasters.W), awQueueDepth, flow = true))) else None
  }

  private def trimId(id: UInt): UInt = id(params.idBits - 1, 0)

  private def idInRange(start: Int, end: Int, id: UInt): Bool = {
    val size = end - start
    if (size <= 1) {
      id === start.U
    } else {
      val delta = log2Floor(start ^ (end - 1))
      val common = delta + 1
      val uncommonMask = (1 << common) - 1
      val uncommon = (id | 0.U(common.W))(delta, 0)
      (id >> common) === (start >> common).U &&
      (start & uncommonMask).U <= uncommon &&
      uncommon <= ((end - 1) & uncommonMask).U
    }
  }

  private def leftOR(value: UInt, width: Int, cap: Int = 999999): UInt = {
    val stop = math.min(width, cap)
    def helper(shift: Int, acc: UInt): UInt =
      if (shift >= stop) acc else helper(shift + shift, acc | (acc << shift)(width - 1, 0))
    helper(1, value)(width - 1, 0)
  }

  private def rightOR(value: UInt, width: Int, cap: Int = 999999): UInt = {
    val stop = math.min(width, cap)
    def helper(shift: Int, acc: UInt): UInt =
      if (shift >= stop) acc else helper(shift + shift, acc | (acc >> shift))
    helper(1, value)(width - 1, 0)
  }

  // Irrevocable round-robin. Holds the winner while valid && !ready, then picks again.
  // A multi-beat burst is not locked; each beat arbitrates, matching AXI4Xbar.
  private def roundRobin(width: Int, valids: UInt, select: Bool): UInt =
    if (width == 1) {
      1.U(1.W)
    } else {
      val valid = valids(width - 1, 0)
      val mask = RegInit(((BigInt(1) << width) - 1).U(width.W))
      val filter = Cat(valid & ~mask, valid)
      val unready = (rightOR(filter, width * 2, width) >> 1) | (mask << width)
      val readys = ~((unready >> width) & unready(width - 1, 0))
      when(select && valid.orR) {
        mask := leftOR(readys & valid, width)
      }
      readys(width - 1, 0)
    }

  private def arbitrate[T <: Data](sink: DecoupledIO[T], sources: Seq[DecoupledIO[T]]): Vec[Bool] = {
    require(sources.nonEmpty)
    val idle = RegInit(true.B)
    val valids = sources.map(_.valid)
    val anyValid = valids.reduce(_ || _)
    val readys = VecInit(roundRobin(valids.size, Cat(valids.reverse), idle).asBools)
    val winner = VecInit(readys.zip(valids).map { case (ready, valid) => ready && valid })
    val prefix = winner.scanLeft(false.B)(_ || _).init
    assert(prefix.zip(winner).map { case (earlier, bit) => !earlier || !bit }.reduce(_ && _))
    assert(!anyValid || winner.asUInt.orR)

    val state = RegInit(VecInit(Seq.fill(sources.size)(false.B)))
    val chosen = Mux(idle, winner, state)
    state := chosen
    when(anyValid) { idle := false.B }
    when(sink.fire) { idle := true.B }

    if (sources.size > 1) {
      val allowed = Mux(idle, readys, state)
      sources.zip(allowed).foreach { case (source, allow) =>
        source.ready := sink.ready && allow
      }
    } else {
      sources.head.ready := sink.ready
    }
    sink.valid := Mux(idle, anyValid, Mux1H(state, valids))
    sink.bits := Mux1H(chosen, sources.map(_.bits))
    chosen
  }

  private def fanout[T <: Data](input: DecoupledIO[T], select: Seq[Bool]): Seq[DecoupledIO[T]] = {
    val filtered = Wire(Vec(select.size, chiselTypeOf(input)))
    filtered.zip(select).foreach { case (port, take) =>
      port.bits := input.bits
      port.valid := input.valid && take
    }
    // One-entry Mux1H ignores its select. Force ready low when nothing matches.
    input.ready := filtered.map(_.ready).zip(select).map { case (ready, take) => ready && take }.reduce(_ || _)
    filtered
  }

  private def transpose[T](rows: Seq[Seq[T]]): Seq[Seq[T]] =
    Seq.tabulate(rows.head.size)(col => rows.map(_(col)))

  for (i <- 0 until nMasters) {
    val (idStart, _) = idRanges(i)
    val wideAr = io.in(i).ar.bits.id.pad(outIdBits) | idStart.U(outIdBits.W)
    val wideAw = io.in(i).aw.bits.id.pad(outIdBits) | idStart.U(outIdBits.W)

    inAr(i).bits.addr := io.in(i).ar.bits.addr
    inAr(i).bits.len := io.in(i).ar.bits.len
    inAr(i).bits.size := io.in(i).ar.bits.size
    inAr(i).bits.burst := io.in(i).ar.bits.burst
    inAr(i).bits.id := wideAr
    inAw(i).bits.addr := io.in(i).aw.bits.addr
    inAw(i).bits.len := io.in(i).aw.bits.len
    inAw(i).bits.size := io.in(i).aw.bits.size
    inAw(i).bits.burst := io.in(i).aw.bits.burst
    inAw(i).bits.id := wideAw
    inW(i).bits := io.in(i).w.bits

    if (nSlaves == 1) {
      inAr(i).valid := io.in(i).ar.valid
      io.in(i).ar.ready := inAr(i).ready
      inAw(i).valid := io.in(i).aw.valid
      io.in(i).aw.ready := inAw(i).ready
      inW(i).valid := io.in(i).w.valid
      io.in(i).w.ready := inW(i).ready
    } else {
      val arMap = WireDefault(VecInit(Seq.fill(endId)(true.B)))
      val awMap = WireDefault(VecInit(Seq.fill(endId)(true.B)))
      val arHit = UIntToOH(io.in(i).ar.bits.id, endId)
      val awHit = UIntToOH(io.in(i).aw.bits.id, endId)
      val rHit = UIntToOH(io.in(i).r.bits.id, endId)
      val bHit = UIntToOH(io.in(i).b.bits.id, endId)
      val arTag = OHToUInt(VecInit(arSel(i)).asUInt, nSlaves)
      val awTag = OHToUInt(VecInit(awSel(i)).asUInt, nSlaves)

      def track(port: UInt, reqFire: Bool, respFire: Bool): Bool = {
        val count = RegInit(0.U(log2Ceil(maxFlightPerId + 1).W))
        val last = Reg(UInt(log2Ceil(nSlaves).W))
        count := count + reqFire.asUInt - respFire.asUInt
        assert(!respFire || count =/= 0.U)
        assert(!reqFire || count =/= maxFlightPerId.U)
        when(reqFire) { last := port }
        val sameSlave = if (maxFlightPerId == 1) true.B else last === port
        (count === 0.U || sameSlave) && count =/= maxFlightPerId.U
      }

      for (id <- 0 until endId) {
        arMap(id) := track(arTag, arHit(id) && io.in(i).ar.fire, rHit(id) && io.in(i).r.fire && io.in(i).r.bits.last)
        awMap(id) := track(awTag, awHit(id) && io.in(i).aw.fire, bHit(id) && io.in(i).b.fire)
      }
      val allowAr = arMap(io.in(i).ar.bits.id)
      val allowAw = awMap(io.in(i).aw.bits.id)
      val queue = awIn(i).get
      val latched = RegInit(false.B)

      inAr(i).valid := io.in(i).ar.valid && allowAr
      io.in(i).ar.ready := inAr(i).ready && allowAr

      queue.io.enq.bits := VecInit(awSel(i)).asUInt
      queue.io.enq.valid := io.in(i).aw.valid && !latched
      inAw(i).valid := io.in(i).aw.valid && (latched || queue.io.enq.ready) && allowAw
      io.in(i).aw.ready := inAw(i).ready && (latched || queue.io.enq.ready) && allowAw
      when(queue.io.enq.fire) { latched := true.B }
      when(inAw(i).fire) { latched := false.B }

      inW(i).valid := io.in(i).w.valid && queue.io.deq.valid
      io.in(i).w.ready := inW(i).ready && queue.io.deq.valid
      queue.io.deq.ready := io.in(i).w.valid && io.in(i).w.bits.last && inW(i).ready
    }
  }

  for (s <- 0 until nSlaves) {
    outR(s).valid := io.out(s).r.valid
    outR(s).bits := io.out(s).r.bits
    io.out(s).r.ready := outR(s).ready
    outB(s).valid := io.out(s).b.valid
    outB(s).bits := io.out(s).b.bits
    io.out(s).b.ready := outB(s).ready
  }

  private val wSel = (0 until nMasters).map { i =>
    if (nSlaves == 1) Seq(true.B) else awIn(i).get.io.deq.bits.asBools
  }
  private val arBySlave = transpose(inAr.zip(arSel).map { case (port, sel) => fanout(port, sel) })
  private val awBySlave = transpose(inAw.zip(awSel).map { case (port, sel) => fanout(port, sel) })
  private val wBySlave = transpose(inW.zip(wSel).map { case (port, sel) => fanout(port, sel) })
  private val rByMaster = transpose(outR.map { port =>
    fanout(port, idRanges.map { case (start, end) => idInRange(start, end, port.bits.id) })
  })
  private val bByMaster = transpose(outB.map { port =>
    fanout(port, idRanges.map { case (start, end) => idInRange(start, end, port.bits.id) })
  })

  for (s <- 0 until nSlaves) {
    arbitrate(outAr(s), arBySlave(s))
    val awWinner = arbitrate(outAw(s), awBySlave(s))
    if (nMasters == 1) {
      outW(s).valid := wBySlave(s).head.valid
      outW(s).bits := wBySlave(s).head.bits
      wBySlave(s).head.ready := outW(s).ready
    } else {
      val taken = awOut(s).get.io.deq.bits.asBools
      outW(s).valid := Mux1H(taken, wBySlave(s).map(_.valid))
      outW(s).bits := Mux1H(taken, wBySlave(s).map(_.bits))
      wBySlave(s).zip(taken).foreach { case (port, take) =>
        port.ready := outW(s).ready && take
      }
    }

    io.out(s).ar.valid := outAr(s).valid
    io.out(s).ar.bits := outAr(s).bits
    outAr(s).ready := io.out(s).ar.ready

    if (nMasters == 1) {
      io.out(s).aw.valid := outAw(s).valid
      io.out(s).aw.bits := outAw(s).bits
      outAw(s).ready := io.out(s).aw.ready
      io.out(s).w.valid := outW(s).valid
      io.out(s).w.bits := outW(s).bits
      outW(s).ready := io.out(s).w.ready
    } else {
      val queue = awOut(s).get
      val latched = RegInit(false.B)
      queue.io.enq.bits := awWinner.asUInt
      queue.io.enq.valid := outAw(s).valid && !latched
      io.out(s).aw.valid := outAw(s).valid && (latched || queue.io.enq.ready)
      outAw(s).ready := io.out(s).aw.ready && (latched || queue.io.enq.ready)
      when(queue.io.enq.fire) { latched := true.B }
      when(outAw(s).fire) { latched := false.B }

      io.out(s).w.valid := outW(s).valid && queue.io.deq.valid
      outW(s).ready := io.out(s).w.ready && queue.io.deq.valid
      queue.io.deq.ready := outW(s).valid && outW(s).bits.last && io.out(s).w.ready
    }
  }

  for (i <- 0 until nMasters) {
    arbitrate(inR(i), rByMaster(i))
    arbitrate(inB(i), bByMaster(i))
    io.in(i).r.valid := inR(i).valid
    inR(i).ready := io.in(i).r.ready
    io.in(i).r.bits.data := inR(i).bits.data
    io.in(i).r.bits.resp := inR(i).bits.resp
    io.in(i).r.bits.last := inR(i).bits.last
    io.in(i).r.bits.id := trimId(inR(i).bits.id)
    io.in(i).b.valid := inB(i).valid
    inB(i).ready := io.in(i).b.ready
    io.in(i).b.bits.resp := inB(i).bits.resp
    io.in(i).b.bits.id := trimId(inB(i).bits.id)
  }
}
