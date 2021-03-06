package scala.slick.lifted

import scala.slick.ast._
import scala.slick.ast.Util.nodeToNodeOps
import scala.slick.model // workaround until deprecated lifted.ForeignKeyAction is removed

/** A Tag marks a specific row represented by an AbstractTable instance. */
sealed trait Tag {
  /** Return a new instance of the AbstractTable carrying this Tag, with a new path */
  def taggedAs(path: List[Symbol]): AbstractTable[_]
}

/** A Tag for table instances that represent a path */
abstract class RefTag(val path: List[Symbol]) extends Tag

/** A Tag marking the base table instance itself */
trait BaseTag extends Tag

/** The driver-independent superclass of all table row objects.
  * @tparam T Row type for this table. Make sure it matches the type of your `*` projection. */
abstract class AbstractTable[T](val tableTag: Tag, val schemaName: Option[String], val tableName: String) extends ColumnBase[T] {
  /** The client-side type of the table as defined by its * projection */
  type TableElementType

  def tableIdentitySymbol: TableIdentitySymbol

  lazy val tableNode = TableNode(schemaName, tableName, tableIdentitySymbol, this)

  def encodeRef(path: List[Symbol]) = tableTag.taggedAs(path).asInstanceOf[AbstractTable[T]]

  /** The * projection of the table used as default for queries and inserts.
    * Should include all columns as a tuple, HList or custom shape and optionally
    * map them to a custom entity type using the <> operator.
    * The `ProvenShape` return type ensures that
    * there is a `Shape` available for translating between the `Column`-based
    * type in * and the client-side type without `Column` in the table's type
    * parameter. */
  def * : ProvenShape[T]

  override def toNode = tableTag match {
    case _: BaseTag =>
      val sym = new AnonSymbol
      TableExpansion(sym, tableNode, ProductNode(Vector(tableTag.taggedAs(List(sym)).*.toNode)).flatten)
    case t: RefTag => Path(t.path)
  }

  def create_* : Iterable[FieldSymbol] = collectFieldSymbols(*.toNode)

  protected[this] def collectFieldSymbols(n: Node): Iterable[FieldSymbol] =
    n.collect {
      case Select(Ref(IntrinsicSymbol(in)), f: FieldSymbol) if in == tableNode => f
    }.toSeq.distinct

  /** Define a foreign key relationship.
    *
    * @param name The name of the foreign key in the database (only used when
    *             you define the database schema with Slick).
    * @param sourceColumns A column or a projection of multiple columns from
    *                      this table defining the source of the foreign key.
    * @param targetTableQuery The `TableQuery` for the target table.
    * @param targetColumns A function that maps from the target table to the
    *                      column (or columns) to which the foreign key points.
    * @param onUpdate A `ForeignKeyAction`, default being `NoAction`.
    * @param onDelete A `ForeignKeyAction`, default being `NoAction`.
    */
  def foreignKey[P, PU, TT <: AbstractTable[_], U]
      (name: String, sourceColumns: P, targetTableQuery: TableQuery[TT])
      (targetColumns: TT => P, onUpdate: model.ForeignKeyAction = model.ForeignKeyAction.NoAction,
       onDelete: model.ForeignKeyAction = model.ForeignKeyAction.NoAction)(implicit unpack: Shape[_ <: ShapeLevel.Flat, TT, U, _], unpackp: Shape[_ <: ShapeLevel.Flat, P, PU, _]): ForeignKeyQuery[TT, U] = {
    val targetTable: TT = targetTableQuery.unpackable.value
    val q = Query[TT, U, TT](targetTable)(Shape.repShape.asInstanceOf[Shape[ShapeLevel.Flat, TT, U, TT]])
    val generator = new AnonSymbol
    val aliased = q.unpackable.encodeRef(generator :: Nil)
    val fv = Library.==.typed[Boolean](unpackp.toNode(targetColumns(aliased.value)), unpackp.toNode(sourceColumns))
    val fk = ForeignKey(name, toNode, q.unpackable.asInstanceOf[ShapedValue[TT, _]],
      targetTable, unpackp, sourceColumns, targetColumns, onUpdate, onDelete)
    new ForeignKeyQuery[TT, U](Filter.ifRefutable(generator, q.toNode, fv), q.unpackable, IndexedSeq(fk), q, generator, aliased.value)
  }

  /** Define the primary key for this table.
    * It is usually simpler to use the `O.PrimaryKey` option on the primary
    * key column but this method allows you to define compound primary keys
    * or give them user-defined names (when defining the database schema
    * with Slick). */
  def primaryKey[T](name: String, sourceColumns: T)(implicit shape: Shape[_ <: ShapeLevel.Flat, T, _, _]): PrimaryKey = PrimaryKey(name, ExtraUtil.linearizeFieldRefs(shape.toNode(sourceColumns)))

  def tableConstraints: Iterator[Constraint] = for {
      m <- getClass().getMethods.iterator
      if m.getParameterTypes.length == 0 && classOf[Constraint].isAssignableFrom(m.getReturnType)
      q = m.invoke(this).asInstanceOf[Constraint]
    } yield q

  final def foreignKeys: Iterable[ForeignKey] =
    tableConstraints.collect{ case q: ForeignKeyQuery[_, _] => q.fks }.flatten.toIndexedSeq

  final def primaryKeys: Iterable[PrimaryKey] =
    tableConstraints.collect{ case k: PrimaryKey => k }.toIndexedSeq

  /** Define an index or a unique constraint. */
  def index[T](name: String, on: T, unique: Boolean = false)(implicit shape: Shape[_ <: ShapeLevel.Flat, T, _, _]) = new Index(name, this, ExtraUtil.linearizeFieldRefs(shape.toNode(on)), unique)

  def indexes: Iterable[Index] = (for {
      m <- getClass().getMethods.view
      if m.getReturnType == classOf[Index] && m.getParameterTypes.length == 0
    } yield m.invoke(this).asInstanceOf[Index])
}
