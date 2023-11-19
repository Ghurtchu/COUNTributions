import scala.collection.parallel.CollectionConverters._


case class Contributor(id: String, score: Int)

val v = Vector(Contributor("1", 5), Contributor("2", 5), Contributor("1", 10))

v.par
  .groupBy(_.id)
  .map { case (k, v) => v.foldLeft(0) { _ + _.score } }
  .toVector
  .par
  .sorted