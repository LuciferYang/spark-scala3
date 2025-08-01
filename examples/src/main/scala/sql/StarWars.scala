package sql

import buildinfo.BuildInfo.inputDirectory
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.apache.spark.sql.functions.col

import scala3encoders.given

object StarWarsData:
  val spark = SparkSession.builder().master("local").getOrCreate
  import spark.implicits._

  case class Friends(name: String, friends: String)
  val friends: Dataset[Friends] = Seq(
    ("Yoda", "Obi-Wan Kenobi"),
    ("Anakin Skywalker", "Sheev Palpatine"),
    ("Luke Skywalker", "Han Solo, Leia Skywalker"),
    ("Leia Skywalker", "Obi-Wan Kenobi"),
    ("Sheev Palpatine", "Anakin Skywalker"),
    ("Han Solo", "Leia Skywalker, Luke Skywalker, Obi-Wan Kenobi, Chewbacca"),
    ("Obi-Wan Kenobi", "Yoda, Qui-Gon Jinn"),
    ("R2-D2", "C-3PO"),
    ("C-3PO", "R2-D2"),
    ("Darth Maul", "Sheev Palpatine"),
    ("Chewbacca", "Han Solo"),
    ("Lando Calrissian", "Han Solo"),
    ("Jabba", "Boba Fett")
  ).map(Friends.apply).toDS

  case class FriendsMissing(who: String, friends: Option[String])
  val dsMissing: Dataset[FriendsMissing] = Seq(
    ("Yoda", Some("Obi-Wan Kenobi")),
    ("Anakin Skywalker", Some("Sheev Palpatine")),
    ("Luke Skywalker", Option.empty[String]),
    ("Leia Skywalker", Some("Obi-Wan Kenobi")),
    ("Sheev Palpatine", Some("Anakin Skywalker")),
    ("Han Solo", Some("Leia Skywalker, Luke Skywalker, Obi-Wan Kenobi"))
  ).map(FriendsMissing.apply).toDS

  case class Character(
      name: String,
      height: Double,
      weight: Option[Double],
      eyecolor: Option[String],
      haircolor: Option[String],
      jedi: Boolean,
      species: String
  )

  val df = spark.sqlContext.read
    .option("header", "true")
    .option("delimiter", ",")
    .option("inferSchema", "true")
    .csv(s"${inputDirectory.getPath}/starwars.csv")

  // going via java interface here of DataFrame map function here
  val characters = df
    .select(
      classOf[Character].getDeclaredFields
        .filterNot(_.isSynthetic)
        .map(_.getName)
        .map(col): _*
    )
    .map {
      case Row(
            name: String,
            height: Double,
            weight: String,
            eyecolor: String,
            haircolor: String,
            jedi: String,
            species: String
          ) =>
        def getOpt(s: String): Option[String] = s match {
          case "NA" => None
          case s    => Some(s)
        }
        def getDoubleOpt(s: String): Option[Double] = getOpt(s).map(_.toDouble)

        Character(
          name,
          height,
          getDoubleOpt(weight),
          getOpt(eyecolor),
          getOpt(haircolor),
          jedi == "jedi",
          species
        )
    }

  val sw_df = characters.join(friends, Seq("name"))

  case class SW(
      name: String,
      height: Double,
      weight: Option[Double],
      eyecolor: Option[String],
      haircolor: Option[String],
      jedi: String,
      species: String,
      friends: String
  )

  val sw_ds = sw_df.as[SW]

object StarWars extends App:
  import StarWarsData._
  try
    friends.show()
    dsMissing.show()
    characters.show()
    sw_ds.show()
  finally spark.close()
