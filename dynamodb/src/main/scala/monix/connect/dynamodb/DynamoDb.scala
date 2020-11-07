/*
 * Copyright (c) 2020-2020 by The Monix Connect Project Developers.
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

package monix.connect.dynamodb

import cats.effect.Resource
import monix.connect.aws.auth.AppConf
import monix.connect.dynamodb.domain.{GetItemSettings, RestoreTableToPointInTimeSettings, RetryStrategy}
import monix.connect.dynamodb.DynamoDbOp.create
import monix.connect.dynamodb.DynamoDbOp.Implicits._
import monix.connect.dynamodb.domain._
import monix.eval.Task
import monix.execution.annotations.UnsafeBecauseImpure
import monix.reactive.{Consumer, Observable}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeDefinition,
  AttributeValue,
  AutoScalingSettingsUpdate,
  BackupTypeFilter,
  BatchGetItemResponse,
  BatchWriteItemResponse,
  BillingMode,
  ContributorInsightsAction,
  CreateBackupResponse,
  CreateGlobalTableResponse,
  CreateTableResponse,
  DescribeBackupResponse,
  DynamoDbRequest,
  DynamoDbResponse,
  GetItemResponse,
  GlobalSecondaryIndexAutoScalingUpdate,
  GlobalTableGlobalSecondaryIndexSettingsUpdate,
  KeySchemaElement,
  KeysAndAttributes,
  ListBackupsResponse,
  ListContributorInsightsResponse,
  ListGlobalTablesResponse,
  ListTablesResponse,
  PointInTimeRecoverySpecification,
  PutItemRequest,
  PutItemResponse,
  Replica,
  ReplicaAutoScalingUpdate,
  ReplicaSettingsUpdate,
  ReplicaUpdate,
  RestoreTableFromBackupResponse,
  ReturnConsumedCapacity,
  ReturnItemCollectionMetrics,
  Tag,
  TransactGetItemsResponse,
  UntagResourceResponse,
  UpdateContinuousBackupsResponse,
  UpdateGlobalTableResponse,
  WriteRequest
}

import scala.concurrent.duration.FiniteDuration

/**
  * An idiomatic DynamoDb client integrated with Monix ecosystem.
  *
  * It is built on top of the [[DynamoDbAsyncClient]], reason why all the exposed methods
  * expect an implicit instance of the client to be in the scope of the call.
  */
object DynamoDb { self =>

  /**
    * Creates a [[Resource]] that will use the values from a
    * configuration file to allocate and release a [[DynamoDb]].
    * Thus, the api expects an `application.conf` file to be present
    * in the `resources` folder.
    *
    * @see how does the expected `.conf` file should look like
    *      https://github.com/monix/monix-connect/blob/master/aws-auth/src/main/resources/reference.conf`
    *
    * @see the cats effect resource data type: https://typelevel.org/cats-effect/datatypes/resource.html
    *
    * @return a [[Resource]] of [[Task]] that allocates and releases [[DynamoDb]].
    */
  def fromConfig: Resource[Task, DynamoDb] = {
    Resource.make {
      for {
        clientConf  <- Task.eval(AppConf.loadOrThrow)
        asyncClient <- Task.now(AsyncClientConversions.fromMonixAwsConf(clientConf.monixAws))
      } yield {
        self.createUnsafe(asyncClient)
      }
    } { _.close }
  }

  /**
    * Creates a [[Resource]] that will use the passed
    * AWS configurations to allocate and release [[DynamoDb]].
    * Thus, the api expects an `application.conf` file to be present
    * in the `resources` folder.
    *
    * @see how does the expected `.conf` file should look like
    *      https://github.com/monix/monix-connect/blob/master/aws-auth/src/main/resources/reference.conf`
    *
    * @see the cats effect resource data type: https://typelevel.org/cats-effect/datatypes/resource.html
    *
    * ==Example==
    *
    * {{{
    *   import cats.effect.Resource
    *   import monix.eval.Task
    *   import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
    *   import software.amazon.awssdk.regions.Region
    *
    *   val defaultCredentials = DefaultCredentialsProvider.create()
    *   val s3Resource: Resource[Task, DynamoDb] = DynamoDb.create(defaultCredentials, Region.AWS_GLOBAL)
    * }}}
    *
    * @param credentialsProvider Strategy for loading credentials and authenticate to AWS S3
    * @param region An Amazon Web Services region that hosts a set of Amazon services.
    * @param endpoint The endpoint with which the SDK should communicate.
    * @param httpClient Sets the [[SdkAsyncHttpClient]] that the SDK service client will use to make HTTP calls.
    * @return a [[Resource]] of [[Task]] that allocates and releases [[S3]].
    **/
  def create(
    credentialsProvider: AwsCredentialsProvider,
    region: Region,
    endpoint: Option[String] = None,
    httpClient: Option[SdkAsyncHttpClient] = None): Resource[Task, DynamoDb] = {
    Resource.make {
      Task.eval {
        val asyncClient = AsyncClientConversions.from(credentialsProvider, region, endpoint, httpClient)
        createUnsafe(asyncClient)
      }
    } { _.close }
  }

  /**
    * Creates a instance of [[DynamoDb]] out of a [[DynamoDbAsyncClient]].
    *
    * It provides a fast forward access to the [[DynamoDb]] that avoids
    * dealing with [[Resource]].
    *
    * Unsafe because the state of the passed [[DynamoDbAsyncClient]] is not guaranteed,
    * it can either be malformed or closed, which would result in underlying failures.
    *
    * @see [[DynamoDb.fromConfig]] and [[DynamoDb.create]] for a pure usage of [[DynamoDb]].
    * They both will make sure that the s3 connection is created with the required
    * resources and guarantee that the client was not previously closed.
    *
    * ==Example==
    *
    * {{{
    *   import java.time.Duration
    *   import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
    *   import software.amazon.awssdk.regions.Region.AWS_GLOBAL
    *   import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
    * }}}
    *
    * @param dynamoDbAsyncClient an instance of a [[S3AsyncClient]].
    * @return An instance of [[S3]]
    */
  @UnsafeBecauseImpure
  def createUnsafe(dynamoDbAsyncClient: DynamoDbAsyncClient): DynamoDb = {
    new DynamoDb {
      override val asyncClient: DynamoDbAsyncClient = dynamoDbAsyncClient
    }
  }

  @deprecated("moved to the trait for safer usage")
  def consumer[In <: DynamoDbRequest, Out <: DynamoDbResponse](
    retries: Int = 0,
    delayAfterFailure: Option[FiniteDuration] = None)(
    implicit
    dynamoDbOp: DynamoDbOp[In, Out],
    client: DynamoDbAsyncClient): Consumer[In, Unit] = DynamoDbSubscriber(retries, delayAfterFailure)

  @deprecated("moved to the trait for safer usage")
  def transformer[In <: DynamoDbRequest, Out <: DynamoDbResponse](
    retries: Int = 0,
    delayAfterFailure: Option[FiniteDuration] = None)(
    implicit
    dynamoDbOp: DynamoDbOp[In, Out],
    client: DynamoDbAsyncClient): Observable[In] => Observable[Out] = { inObservable: Observable[In] =>
    inObservable.mapEval(request => DynamoDbOp.create(request, retries, delayAfterFailure))
  }

}

trait DynamoDb { self =>

  private[dynamodb] implicit val asyncClient: DynamoDbAsyncClient

  /**
    * Pre-built [[Consumer]] implementation that expects and executes [[DynamoDbRequest]]s.
    * It provides with the flexibility of retrying a failed execution with delay to recover from it.
    *
    * @param dynamoDbOp abstracts the execution of any given [[DynamoDbRequest]] with its correspondent operation that returns [[DynamoDbResponse]].
    * @tparam In the input request as type parameter lower bounded by [[DynamoDbRequest]].
    * @tparam Out output type parameter that must be a subtype os [[DynamoDbRequest]].
    * @return A [[monix.reactive.Consumer]] that expects and executes dynamodb requests.
    */
  def consumer[In <: DynamoDbRequest, Out <: DynamoDbResponse](
    retries: Int = 0,
    delayAfterFailure: Option[FiniteDuration] = None)(implicit dynamoDbOp: DynamoDbOp[In, Out]): Consumer[In, Unit] =
    DynamoDbSubscriber(retries, delayAfterFailure)

  /**
    * Transformer that executes any given [[DynamoDbRequest]] and transforms them to its subsequent [[DynamoDbResponse]] within [[Task]].
    * It also provides with the flexibility of retrying a failed execution with delay to recover from it.
    *
    * @param retries the number of times that an operation can be retried before actually returning a failed [[Task]].
    *        it must be higher or equal than 0.
    * @param delayAfterFailure delay after failure for the execution of a single [[DynamoDbOp]].
    * @param dynamoDbOp implicit [[DynamoDbOp]] that abstracts the execution of the specific operation.
    * @tparam In input type parameter that must be a subtype os [[DynamoDbRequest]].
    * @tparam Out output type parameter that will be a subtype os [[DynamoDbRequest]].
    * @return DynamoDb operation transformer: `Observable[DynamoDbRequest] => Observable[DynamoDbRequest]`.
    */
  def transformer[In <: DynamoDbRequest, Out <: DynamoDbResponse](
    retries: Int = 0,
    delayAfterFailure: Option[FiniteDuration] = None)(
    implicit dynamoDbOp: DynamoDbOp[In, Out]): Observable[In] => Observable[Out] = { inObservable: Observable[In] =>
    inObservable.mapEval(single(_, retries, delayAfterFailure))
  }

  /**
    * Creates the description of the execution of a single request that
    * under failure it will be retried as many times as set in [[retries]].
    *
    * @param request the [[DynamoDbRequest]] that will be executed.
    * @param retries the number of times that an operation can be retried before actually returning a failed [[Task]].
    *        it must be higher or equal than 0.
    * @param delayAfterFailure delay after failure for the execution of a single [[DynamoDbOp]].
    * @param dynamoDbOp an implicit [[DynamoDbOp]] that abstracts the execution of the specific operation.
    * @return A [[Task]] that ends successfully with the response as [[DynamoDbResponse]], or a failed one.
    */
  def single[In <: DynamoDbRequest, Out <: DynamoDbResponse](
    request: In,
    retries: Int = 0,
    delayAfterFailure: Option[FiniteDuration] = None)(implicit dynamoDbOp: DynamoDbOp[In, Out]): Task[Out] = {

    require(retries >= 0, "Retries per operation must be higher or equal than 0.")

    Task
      .defer(dynamoDbOp(request))
      .onErrorHandleWith { ex =>
        val t = Task
          .defer(
            if (retries > 0) single(request, retries - 1, delayAfterFailure)
            else Task.raiseError(ex))
        delayAfterFailure match {
          case Some(delay) => t.delayExecution(delay)
          case None => t
        }
      }
  }

  /** Closes the underlying [[S3AsyncClient]] */
  def close: Task[Unit] = Task.evalOnce(asyncClient.close())

}
