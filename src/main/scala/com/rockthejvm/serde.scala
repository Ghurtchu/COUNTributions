package com.rockthejvm

import com.rockthejvm.domain.*
import cats.syntax.all.*
import play.api.libs.json.*
import play.api.libs.*
import Reads.{IntReads, StringReads}

object serde {
  given ReadsPublicRepos: Reads[PublicRepos] =
    (__ \ "public_repos").read[Int].map(PublicRepos.apply)

  given ReadsRepo: Reads[RepoName] =
    (__ \ "name").read[String].map(RepoName.apply)

  given ReadsContributor: Reads[Contributor] = json =>
    (
      (json \ "login").asOpt[String],
      (json \ "contributions").asOpt[Long],
    ).tupled.fold(JsError("parse failure"))((lo, co) => JsSuccess(Contributor(lo, co)))

  given WritesContributor: Writes[Contributor] = Json.writes[Contributor]

  given WritesContributions: Writes[Contributions] = Json.writes[Contributions]

  object syntax {
    extension (self: String) def into[A](using r: Reads[A]): A = Json.parse(self).as[A]
    extension [A](self: A) def toJson(using w: Writes[A]): String = Json.prettyPrint(w.writes(self))
  }
}
