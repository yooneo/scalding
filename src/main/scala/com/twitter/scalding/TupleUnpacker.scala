package com.twitter.scalding

import cascading.pipe._
import cascading.pipe.joiner._
import cascading.tuple._

import java.lang.reflect.Method

import scala.reflect.Manifest

/** Base class for objects which unpack an object into a tuple.
  * The packer can verify the arity, types, and also the existence
  * of the getter methods at plan time, without having the job
  * blow up in the middle of a run.
  *
  * @author Argyris Zymnis
  * @author Oscar Boykin
  */
object TupleUnpacker extends LowPriorityTupleUnpackers
abstract class TupleUnpacker[T] extends java.io.Serializable {
  def newSetter(fields : Fields) : TupleSetter[T]
}

trait LowPriorityTupleUnpackers extends TupleConversions {
  implicit def genericUnpacker[T](implicit setter : TupleSetter[T]) = new GenericTupleUnpacker[T](setter)
}

class ReflectionTupleUnpacker[T](implicit m : Manifest[T]) extends TupleUnpacker[T] {
  override def newSetter(fields : Fields) = new ReflectionSetter[T](fields)(m)
}

class ReflectionSetter[T](fields : Fields)(implicit m : Manifest[T]) extends TupleSetter[T] {

  validate // Call the validation method at the submitter

  // This is lazy because it is not serializable
  // Contains a list of methods used to set the Tuple from an input of type T
  lazy val setters = makeSetters

  // Methods and Fields are not serializable so we
  // make these defs instead of vals
  // TODO: filter by isAccessible, which somehow seems to fail
  def methodMap = m.erasure
    .getDeclaredMethods
    // Keep only methods with 0 parameter types
    .filter { m => m.getParameterTypes.length == 0 }
    .groupBy { _.getName }
    .mapValues { _.head }

  // TODO: filter by isAccessible, which somehow seems to fail
  def fieldMap = m.erasure
    .getDeclaredFields
    .groupBy { _.getName }
    .mapValues { _.head }

  def makeSetters = {
    (0 until fields.size).map { idx =>
      val fieldName = fields.get(idx).toString
      setterForFieldName(fieldName)
    }
  }

  // This validation makes sure that the setters exist
  // but does not save them in a val (due to serialization issues)
  def validate = makeSetters

  override def apply(input : T) : Tuple = {
    val values = setters.map { setFn => setFn(input) }
    new Tuple(values : _*)
  }

  override def arity = fields.size

  private def setterForFieldName(fieldName : String) : (T => AnyRef) = {
    getValueFromMethod(createGetter(fieldName))
      .orElse(getValueFromMethod(fieldName))
      .orElse(getValueFromField(fieldName))
      .getOrElse(
        throw new TupleUnpackerException("Unrecognized field: " + fieldName + " for class: " + m.erasure.getName)
      )
  }

  private def getValueFromField(fieldName : String) : Option[(T => AnyRef)] = {
    fieldMap.get(fieldName).map { f => (x : T) => f.get(x) }
  }

  private def getValueFromMethod(methodName : String) : Option[(T => AnyRef)] = {
    methodMap.get(methodName).map { m => (x : T) => m.invoke(x) }
  }

  private def upperFirst(s : String) = s.substring(0,1).toUpperCase + s.substring(1)
  private def createGetter(s : String) = "get" + upperFirst(s)
}

/** This is a generic tuple unpacker that just delegates to a setter.
  *
  * @author Argyris Zymnis
  */
class GenericTupleUnpacker[T](setter : TupleSetter[T]) extends TupleUnpacker[T] {
  override def newSetter(fields : Fields) = {
    assert(fields.size == setter.arity, "setter arity must match number of fields")
    setter
  }
}

class TupleUnpackerException(args : String) extends Exception(args)
