/*
 * Copyright (c) 2020-2021 by The Monix Connect Project Developers.
 * See the project homepage at: https://connect.monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.connect.mongodb.domain.connection

import cats.effect.Resource
import com.mongodb.reactivestreams.client.MongoClient
import monix.connect.mongodb.domain.connection.Connection.{createConnector, fromCodecProvider}
import monix.connect.mongodb.domain.{Collection, MongoConnector, Tuple6F}
import monix.eval.Task
import monix.execution.annotations.UnsafeBecauseImpure

private[mongodb] class Connection6[T1, T2, T3, T4, T5, T6]
  extends Connection[Tuple6F[Collection, T1, T2, T3, T4, T5, T6], Tuple6F[MongoConnector, T1, T2, T3, T4, T5, T6]] { self =>

  @UnsafeBecauseImpure
  override def createUnsafe(client: MongoClient, collections: Tuple6F[Collection, T1, T2, T3, T4, T5, T6])
    : Resource[Task, Tuple6F[MongoConnector, T1, T2, T3, T4, T5, T6]] = {
    Resource.make(Connection6.createConnectors(client, collections))(self.close)
  }

}

private[mongodb] object Connection6 {

  def createConnectors[T1, T2, T3, T4, T5, T6](
    client: MongoClient,
    collections: Tuple6F[Collection, T1, T2, T3, T4, T5, T6]): Task[Tuple6F[MongoConnector, T1, T2, T3, T4, T5, T6]] = {
    val (a, b, c, d ,e ,f) = collections
    for {
      a <- createConnector(client, a, fromCodecProvider(a.codecProvider: _*))
      t <- Connection5.createConnectors(client, (b, c, d, e, f))
    } yield (a, t._1, t._2, t._3, t._4, t._5)
  }
}
