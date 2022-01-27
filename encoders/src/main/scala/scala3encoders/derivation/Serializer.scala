package scala3encoders.derivation

import scala.compiletime
import scala.deriving.Mirror
import scala.reflect.ClassTag

import org.apache.spark.sql.catalyst.expressions.{Expression, KnownNotNull}
import org.apache.spark.sql.catalyst.expressions.objects.Invoke
import org.apache.spark.sql.catalyst.SerializerBuildHelper.*
import org.apache.spark.sql.types.*
import org.apache.spark.sql.catalyst.expressions.objects.UnwrapOption

trait Serializer[T]:
  def inputType: DataType
  def serialize(inputObject: Expression): Expression

object Serializer:
  given Serializer[Double] with
    def inputType: DataType = DoubleType
    def serialize(inputObject: Expression): Expression = inputObject

  given Serializer[Float] with
    def inputType: DataType = FloatType
    def serialize(inputObject: Expression): Expression = inputObject

  given Serializer[Short] with
    def inputType: DataType = ShortType
    def serialize(inputObject: Expression): Expression = inputObject

  given Serializer[Byte] with
    def inputType: DataType = ByteType
    def serialize(inputObject: Expression): Expression = inputObject

  given Serializer[Boolean] with
    def inputType: DataType = BooleanType
    def serialize(inputObject: Expression): Expression = inputObject
 
  given Serializer[String] with
    def inputType: DataType = ObjectType(classOf[String])
    def serialize(inputObject: Expression): Expression = createSerializerForString(inputObject)
  
  given Serializer[Int] with
    def inputType: DataType = IntegerType
    def serialize(inputObject: Expression): Expression = inputObject
  given Serializer[Long] with
    def inputType: DataType = LongType
    def serialize(inputObject: Expression): Expression = inputObject

  inline given deriveOpt[T](using s: Serializer[T]): Serializer[Option[T]] =
    new Serializer[Option[T]]:
      override def inputType: DataType = s.inputType
      override def serialize(inputObject: Expression): Expression =
        s.serialize(UnwrapOption(inputType, inputObject))

  inline given deriveSeq[T](using s: Serializer[T], ct: ClassTag[T]): Serializer[Seq[T]] =
    // TODO: Nullable fields without reflection
    new Serializer[Seq[T]]:
      override def inputType: DataType = ArrayType(s.inputType)
      override def serialize(inputObject: Expression): Expression =
        s.inputType match
          case dt: ObjectType => 
            createSerializerForMapObjects(inputObject, dt, s.serialize(_))
          case dt:  (BooleanType | ByteType | ShortType | IntegerType | LongType |
                   FloatType | DoubleType) =>
            val cls = ct.runtimeClass
            if (cls.isArray && cls.getComponentType.isPrimitive) then
              createSerializerForPrimitiveArray(inputObject, dt)
            else
              createSerializerForGenericArray(inputObject, dt, nullable = true)
          case dt => createSerializerForGenericArray(inputObject, dt, nullable = true)

  inline given deriveArray[T: Serializer: ClassTag]: Serializer[Array[T]] =
    val forSeq = deriveSeq[T]
    new Serializer[Array[T]]:
      override def inputType: DataType = forSeq.inputType
      override def serialize(inputObject: Expression) = forSeq.serialize(inputObject)

  inline given deriveSet[T: Serializer : ClassTag]: Serializer[Set[T]] =
    val forSeq = deriveSeq[T]
    new Serializer[Set[T]]:
      override def inputType: DataType = forSeq.inputType
      override def serialize(inputObject: Expression) =
        val newInput = Invoke(inputObject, "toSeq", ObjectType(classOf[Seq[_]]))
        forSeq.serialize(newInput)

  inline given deriveMap[K, V](using ks: Serializer[K], vs: Serializer[V]): Serializer[Map[K, V]] =
    // TODO: nullables
    new Serializer[Map[K, V]]:
      override def inputType: DataType = MapType(ks.inputType, vs.inputType, true)

      override def serialize(inputObject: Expression) =
        createSerializerForMap(
          inputObject,
          MapElementInformation(
            ks.inputType,
            nullable = true,
            ks.serialize(_)
          ),
          MapElementInformation(
            vs.inputType,
            nullable = true,
            vs.serialize(_)
          )
        )

  inline given derived[T](using m: Mirror.Of[T], ct: ClassTag[T]): Serializer[T] = inline m match
    case p: Mirror.ProductOf[T] => product(p, ct)
    case s: Mirror.SumOf[T] => compiletime.error("cannot derive Serializer for Sum types")

  // inspired by https://github.com/apache/spark/blob/39542bb81f8570219770bb6533c077f44f6cbd2a/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/ScalaReflection.scala#L575-L599
  private inline def product[T](mirror: Mirror.ProductOf[T], classTag: ClassTag[T]): Serializer[T] = 
    val serializers: List[Serializer[?]] = summonTuple[mirror.MirroredElemTypes]
    val labels: List[String] = getElemLabels[mirror.MirroredElemLabels]
    new Serializer[T]:
      override def inputType: DataType = ObjectType(classTag.runtimeClass)
      override def serialize(inputObject: Expression): Expression =
        val fields = labels.zip(serializers)
          .map { (label, serializer) =>
            val fieldInputObject = Invoke(KnownNotNull(inputObject), label, serializer.inputType)
            (label, serializer.serialize(fieldInputObject))
          }
        createSerializerForObject(inputObject, fields)
    
  private inline def summonTuple[T <: Tuple]: List[Serializer[?]] = inline compiletime.erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[Serializer[t]] :: summonTuple[ts]

