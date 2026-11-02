package ansercore.frontend.bpu

import chisel3._
import chisel3.util._


abstract class BPUBundle  extends Bundle with HasBPUParameters
abstract class BPUModule  extends Module with HasBPUParameters


abstract class NamedTuple[T <: Product] {
  protected def asTuple: T

  // override equals and hashCode to allow comparison and Set[NamedTuple] de-duplication
  override def equals(obj: Any): Boolean =
    obj != null &&
      this.getClass == obj.getClass &&
      obj.asInstanceOf[NamedTuple[T]].asTuple == this.asTuple

  override def hashCode(): Int =
    asTuple.hashCode()

  override def toString: String =
    s"${this.getClass.getSimpleName}${asTuple.toString}"
}

