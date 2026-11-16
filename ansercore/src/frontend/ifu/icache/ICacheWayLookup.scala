package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.HasCircularQueuePtrHelper
import ansercore.frontend.FrontendExcept

class ICacheWayLookup extends ICacheModule with HasCircularQueuePtrHelper {
  class WayLookupIO extends ICacheBundle{
    val flush = Input(Bool())
    val lookupPush = Flipped(Decoupled(new WayLookupPacket))
    // s2 拿队头。同拍 s0 也要 pop 时，s0 拿下一条。
    val fetchPop = Decoupled(new WayLookupPacket)
    val icacheRefill = Flipped(Valid(new ICacheRefillInfo))
  }

  val io = IO(new WayLookupIO)

  //-----------------------alias------------------------------//
  val flush = io.flush
  val pushPacket = io.lookupPush.bits
  val pushValid = io.lookupPush.valid
  val fetchPopReady = io.fetchPop.ready
  val icacheRefillValid = io.icacheRefill.valid
  val icacheRefill = io.icacheRefill.bits
  val pushFire = io.lookupPush.fire
  val popFire = io.fetchPop.fire

  //-----------------------fifo-------------------------------//
  // lookup 入队、fetch 出队，两拍不必对齐。异常全队只挂一份，用入队指针指到对应项。
  val entries = Reg(Vec(WayLookupSize, new WayLookupEntry))
  val exceptEntry = RegInit(0.U.asTypeOf(new WayLookupExceptionEntry))
  val pushPtr = RegInit(0.U.asTypeOf(new WayLookupPtr))
  val popPtr = RegInit(0.U.asTypeOf(new WayLookupPtr))
  val full = isFull(pushPtr, popPtr)
  val empty = isEmpty(pushPtr, popPtr)
  val occupied = circularRangMask(popPtr, pushPtr)


  val exceptValid = FrontendExcept.isException(exceptEntry.except)
  // 队里已经有一条异常时，新的 lookup 停住。
  val exceptStall = exceptValid
  val pushReady = !(full || exceptStall)

  when(flush){
    pushPtr := 0.U.asTypeOf(new WayLookupPtr)
  }.elsewhen(pushFire){
    pushPtr := pushPtr + 1.U
  }

  when(flush){
    popPtr := 0.U.asTypeOf(new WayLookupPtr)
  }.elsewhen(popFire){
    popPtr := popPtr + 1.U
  }

  when(flush){
    exceptEntry := 0.U.asTypeOf(new WayLookupExceptionEntry)
  }.elsewhen(pushFire){
    exceptEntry.ptr := pushPtr.value
    exceptEntry.except := pushPacket.exceptInfo
  }

  //-----------------------refill-----------------------------//
  // 同 set 同 tag：补上 refill 的 way。同 set 不同 tag：这条 way 已被换掉。
  def withRefill(info: WayLookupEntry): Vec[Bool] = {
    val cacheable = !info.itlbInfo.isNC && !info.itlbInfo.isMMIO
    val line0Set = info.cacheInfo.vSetIdx
    val line1Set = (line0Set + 1.U)(SetIdxWidth - 1, 0)

    val cacheValid = cacheable && icacheRefillValid
    val line0Hit = cacheValid && line0Set === icacheRefill.setIdx
    val line1Hit = cacheValid && info.cacheInfo.crossCacheline && line1Set === icacheRefill.setIdx

    val out = Wire(Vec(2, Bool()))
    out(0) := info.cacheInfo.inFlying(0) & !line0Hit
    out(1) := info.cacheInfo.inFlying(1) & !line1Hit
    out
  }
  //没问题更新
  entries.zipWithIndex.foreach{ case(entry, idx) =>
    val pushEn = pushFire && pushPtr.value === idx.U
    when(pushEn){
      entry.itlbInfo := pushPacket.wayLookupInfo.itlbInfo
      entry.cacheInfo.vSetIdx := pushPacket.wayLookupInfo.cacheInfo.vSetIdx
      entry.cacheInfo.crossCacheline := pushPacket.wayLookupInfo.cacheInfo.crossCacheline
      entry.cacheInfo.inFlying := pushPacket.wayLookupInfo.cacheInfo.inFlying
      entry.cacheInfo.wayMask := pushPacket.wayLookupInfo.cacheInfo.wayMask
    }

    val base = Mux(pushEn, pushPacket.wayLookupInfo, entry)
    val updateInFlying = withRefill(base)
    when(pushEn || occupied(idx) && icacheRefillValid){
      entry.cacheInfo.inFlying := updateInFlying
    }
  }
  //---------------------pop----------------------------------//
  val popEntry = entries(popPtr.value)
  val popValid = !empty
  val popPacket = Wire(new WayLookupPacket)
  popPacket.wayLookupInfo := popEntry
  popPacket.exceptInfo := Mux(exceptEntry.ptr === popPtr.value, exceptEntry.except, FrontendExcept.none)

  io.lookupPush.ready := pushReady
  io.fetchPop.valid := popValid || pushValid
  io.fetchPop.bits := Mux(empty, pushPacket, popPacket)
}
