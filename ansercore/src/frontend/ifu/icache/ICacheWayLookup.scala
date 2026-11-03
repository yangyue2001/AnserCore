package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.HasCircularQueuePtrHelper

class ICacheWayLookup extends ICacheModule with HasCircularQueuePtrHelper {
  class WayLookupIO extends ICacheBundle{
    val flush = Input(Bool())
    val lookupPush = Flipped(Decoupled(new WayLookupPacket))
    // s2 拿队头。同拍 s0 也要 pop 时，s0 拿下一条。
    val s0Pop = Input(Bool())
    val s2Pop = Input(Bool())
    val s0Entry = Valid(new WayLookupPacket)
    val s2Entry = Valid(new WayLookupPacket)
    val icacheRefill = Flipped(Valid(new ICacheRefillInfo))
  }

  val io = IO(new WayLookupIO)

  //-----------------------alias------------------------------//
  val flush = io.flush
  val lookupPush = io.lookupPush.bits
  val icacheRefillValid = io.icacheRefill.valid
  val icacheRefill = io.icacheRefill.bits

  //-----------------------fifo-------------------------------//
  // lookup 入队、fetch 出队，两拍不必对齐。异常全队只挂一份，用入队指针指到对应项。
  val entries = Reg(Vec(WayLookupSize, new WayLookupEntry))
  val exceptEntry = RegInit(0.U.asTypeOf(new WayLookupExceptionEntry))
  val pushPtr = RegInit(0.U.asTypeOf(new WayLookupPtr))
  val popPtr = RegInit(0.U.asTypeOf(new WayLookupPtr))
  val full = isFull(pushPtr, popPtr)
  val empty = isEmpty(pushPtr, popPtr)
  val occupied = circularRangMask(popPtr, pushPtr)


  val exceptValid = exceptEntry.except.pageFault || exceptEntry.except.accessFault
  // 队里已经有一条异常时，新的 lookup 停住，等这条被 fetch 取走。
  val exceptStall = exceptValid
  val queued = distanceBetween(pushPtr, popPtr)

  // s2 优先。它 pop 掉队头之后，s0 才能看下一条。
  // 不能同拍 bypass：fetch miss 会回到 MSHR 的 lookup ready，再回到这次 push，形成组合环。
  // way 先写入队列，下一拍 fetch 再读。
  val s2FromQueue = queued =/= 0.U
  val s2Valid = s2FromQueue
  val s2Takes = io.s2Pop && s2Valid
  val s0UseNext = s2Takes
  val s0FromQueue = Mux(s0UseNext, queued >= 2.U, queued =/= 0.U)
  val s0Valid = s0FromQueue
  val s0Takes = io.s0Pop && s0Valid
  val s0QueuePtr = Mux(s0UseNext, popPtr + 1.U, popPtr)

  val s2TakesQueue = io.s2Pop && s2FromQueue
  val s0TakesQueue = io.s0Pop && s0FromQueue
  io.lookupPush.ready := (!full || s2TakesQueue || s0TakesQueue) && !exceptStall
  val pushFire = io.lookupPush.fire
  val popNum = Mux(s2Takes, 1.U, 0.U) + Mux(s0Takes, 1.U, 0.U)

  when(flush){
    pushPtr := 0.U.asTypeOf(new WayLookupPtr)
  }.elsewhen(pushFire){
    pushPtr := pushPtr + 1.U
  }

  when(flush){
    popPtr := 0.U.asTypeOf(new WayLookupPtr)
  }.elsewhen(popNum =/= 0.U){
    popPtr := popPtr + popNum
  }

  val s2DropExcept = s2TakesQueue && exceptValid && popPtr.value === exceptEntry.ptr
  val s0DropExcept = s0TakesQueue && exceptValid && s0QueuePtr.value === exceptEntry.ptr
  when(flush || s2DropExcept || s0DropExcept){
    exceptEntry := 0.U.asTypeOf(new WayLookupExceptionEntry)
  }.elsewhen(pushFire){
    exceptEntry.ptr := pushPtr.value
    exceptEntry.except := lookupPush.exceptInfo
  }
  //-----------------------refill-----------------------------//
  // 同 set 同 tag：补上 refill 的 way。同 set 不同 tag：这条 way 已被换掉。
  def withRefill(info: WayLookupEntry): WayLookupEntry = {
    val out = Wire(new WayLookupEntry)
    out := info
    val cacheable = !info.itlbInfo.isNC && !info.itlbInfo.isMMIO
    val line0Set = info.cacheInfo.vSetIdx
    val line1Set = (line0Set + 1.U)(SetIdxWidth - 1, 0)
    val tagHit = cacheable && icacheRefillValid && info.itlbInfo.pTag === icacheRefill.pTag
    val tagMiss = cacheable && icacheRefillValid && info.itlbInfo.pTag =/= icacheRefill.pTag
    val line0Hit = tagHit && line0Set === icacheRefill.setIdx
    val line1Hit = tagHit && info.cacheInfo.crossCacheline && line1Set === icacheRefill.setIdx
    val line0Kill = tagMiss && line0Set === icacheRefill.setIdx
    val line1Kill = tagMiss && info.cacheInfo.crossCacheline && line1Set === icacheRefill.setIdx

    out.cacheInfo.wayMask(0) := Mux(
      line0Hit,
      info.cacheInfo.wayMask(0) | icacheRefill.wayMask,
      Mux(line0Kill, info.cacheInfo.wayMask(0) & (~icacheRefill.wayMask), info.cacheInfo.wayMask(0)),
    )
    out.cacheInfo.wayMask(1) := Mux(
      line1Hit,
      info.cacheInfo.wayMask(1) | icacheRefill.wayMask,
      Mux(line1Kill, info.cacheInfo.wayMask(1) & (~icacheRefill.wayMask), info.cacheInfo.wayMask(1)),
    )

    out
  }

  entries.zipWithIndex.foreach{ case (entry, idx) =>
    val pushEn = pushFire && pushPtr.value === idx.U
    val base = Mux(pushEn, lookupPush.wayLookupInfo, entry)
    val updated = withRefill(base)

    when(pushEn){
      entry := updated
    }.elsewhen(occupied(idx) && icacheRefillValid){
      entry.cacheInfo.wayMask := updated.cacheInfo.wayMask
    }
  }

  //-----------------------output-----------------------------//
  def storedExcept(ptr: UInt): WayLookupException = {
    val except = Wire(new WayLookupException)
    val hit = exceptValid && ptr === exceptEntry.ptr
    except.pageFault := hit && exceptEntry.except.pageFault
    except.accessFault := hit && exceptEntry.except.accessFault
    except
  }

  val s2Base = entries(popPtr.value)
  val s2Packet = Wire(new WayLookupPacket)
  s2Packet.wayLookupInfo := withRefill(s2Base)
  s2Packet.exceptInfo := storedExcept(popPtr.value)

  val s0Base = entries(s0QueuePtr.value)
  val s0Packet = Wire(new WayLookupPacket)
  s0Packet.wayLookupInfo := withRefill(s0Base)
  s0Packet.exceptInfo := storedExcept(s0QueuePtr.value)

  io.s2Entry.valid := s2Valid
  io.s2Entry.bits := s2Packet
  io.s0Entry.valid := s0Valid
  io.s0Entry.bits := s0Packet

  //-----------------------assert-----------------------------//
  assert(flush || !io.s2Pop || s2Valid, "wayLookup s2 pop when empty")
  assert(flush || !io.s0Pop || s0Valid, "wayLookup s0 pop when empty")
}
