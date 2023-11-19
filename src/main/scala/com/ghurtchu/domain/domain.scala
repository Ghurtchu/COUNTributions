package com.ghurtchu.domain

import play.api.libs.json._


object domain {

  case class PublicRepos(value: Int) extends AnyVal
  implicit val readsPublicRepos: Reads[PublicRepos] = json =>
    (json \ "public_repos")
      .validateOpt[Int]
      .map(_.fold(PublicRepos(0))(PublicRepos(_)))


  case class RepositoryName(value: String) extends AnyVal
  implicit val readsRepositoryName: Reads[RepositoryName] = json =>
    (json \ "name")
      .validateOpt[String]
      .map(_.getOrElse("unknown"))
      .map(RepositoryName(_))


  case class Contributor(login: String, contributions: Long)
  object Contributor {
    def fromTuple: ((String, Long)) => Contributor = Contributor(_, _)
  }


  implicit val readsContributor: Reads[Contributor] = json =>
    for {
      login <- (json \ "login").validateOpt[String]
        .map(_.getOrElse("unknown"))
      contributions <- (json \ "contributions")
        .validateOpt[Long]
        .map(_.getOrElse(0L))
    } yield Contributor(login, contributions)


}
