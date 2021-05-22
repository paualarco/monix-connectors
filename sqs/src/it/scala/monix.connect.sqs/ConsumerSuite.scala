package monix.connect.sqs

import monix.connect.sqs.domain.QueueName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ConsumerSuite extends AnyFlatSpecLike with Matchers with ScalaFutures with SqsFixture {

  implicit val defaultConfig: PatienceConfig = PatienceConfig(10.seconds, 300.milliseconds)
  implicit val sqsClient: Sqs = Sqs.createUnsafe(asyncClient)
  val queueName: QueueName = genQueueName.sample.get

  "receiveSingleManualDelete" should "consume up to 10 messages at a time" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.listOfN(15, genFifoMessage(defaultGroupId)).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Task.traverse(messages)(sqs.producer.sendSingleMessage(_, queueUrl))
        receivedMessages1 <- sqs.consumer.receiveSingleManualDelete(queueUrl, inFlightMessages = 1)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()))
        receivedMessages10 <- sqs.consumer.receiveSingleManualDelete(queueUrl, inFlightMessages = 10)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()))
        receivedMessages4 <- sqs.consumer.receiveSingleManualDelete(queueUrl, inFlightMessages = 10)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()))
      } yield {
        receivedMessages1.size shouldBe 1
        receivedMessages10.size shouldBe 10
        receivedMessages4.size shouldBe 4
        (receivedMessages1 ++ receivedMessages10 ++ receivedMessages4).map(_.body) should contain theSameElementsAs messages.map(_.body)
      }
    }.runSyncUnsafe()
  }

  it should "respects the specified `inFlight` messages on the same consumer or visibility timeout being exceeded" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.listOfN(15, genFifoMessage(defaultGroupId)).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Task.traverse(messages)(sqs.producer.sendSingleMessage(_, queueUrl))
        receivedMessages1 <- sqs.consumer.receiveSingleManualDelete(queueUrl, inFlightMessages = 10, visibilityTimeout = 15.seconds)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()).delayExecution(2.seconds).startAndForget)
        //the following receive calls will return empty since the previous messages were not yet processed and deleted
        inFlightMessagesReached1 <- sqs.consumer.receiveSingleManualDelete(queueUrl)
        inFlightMessagesReached2 <- Task.sleep(1.seconds) >> sqs.consumer.receiveSingleManualDelete(queueUrl)
        receivedMessages2 <- Task.sleep(3.seconds) >> sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 1.second)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()).delayExecution(3.seconds).startAndForget)
        // here already we consumed all messages, but since the deletion of the previous consumption
        // took longer than the visibility timeout, the messages will be consumed again.
        receivedMessages3 <- Task.sleep(2.seconds) >> sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 15.seconds)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()))
      } yield {
        receivedMessages1.size shouldBe 10
        inFlightMessagesReached1 shouldBe List.empty
        inFlightMessagesReached2 shouldBe List.empty
        receivedMessages2.size shouldBe 5
        receivedMessages3.size shouldBe 5
        (receivedMessages1 ++ receivedMessages2 ++ receivedMessages3).map(_.body).toSet should contain theSameElementsAs messages.map(_.body)
        receivedMessages2.map(_.body) should contain theSameElementsAs receivedMessages3.map(_.body)
      }
    }.runSyncUnsafe()
  }

  it should "avoid consuming the same message multiple times when `deleteFromQueue` is triggered and `visibilityTimeout` is exceeded" in {
    val queueName = genFifoQueueName.sample.get
    val message = genFifoMessage(defaultGroupId).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- sqs.producer.sendSingleMessage(message, queueUrl)
        receivedMessage <- sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 1.seconds)
          .tapEval(Task.traverse(_)(_.deleteFromQueue()))
        receivedMessagesAfterDeleteFromQueue <- Task.sleep(3.seconds) >> sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 10.seconds)
      } yield {
        receivedMessage.size shouldBe 1
        receivedMessage.map(_.body) shouldBe List(message.body)
        receivedMessagesAfterDeleteFromQueue shouldBe List.empty
      }
    }.runSyncUnsafe()
  }

  it can "consume the same message multiple times when `deleteFromQueue` is not triggered and `visibilityTimeout` is exceeded" in {
    val queueName = genFifoQueueName.sample.get
    val message = genFifoMessage(defaultGroupId).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- sqs.producer.sendSingleMessage(message, queueUrl)
        receivedMessage <- sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 1.seconds)
        duplicatedMessage <- Task.sleep(3.seconds) >> sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 10.seconds)
        emptyUnderVisibilityTimeout <- sqs.consumer.receiveSingleManualDelete(queueUrl, visibilityTimeout = 5.seconds)
      } yield {
        receivedMessage.size shouldBe 1
        receivedMessage.map(_.body) shouldBe List(message.body)
        duplicatedMessage.size shouldBe 1
        duplicatedMessage.map(_.body) shouldBe List(message.body)
        emptyUnderVisibilityTimeout shouldBe List.empty
      }
    }.runSyncUnsafe()
  }

  it should "wait as indicated in `waitTimeSeconds` until a message arrives" in {
    val queueName = genFifoQueueName.sample.get
    val message1 = genFifoMessage(defaultGroupId).sample.get
    val message2 = genFifoMessage(defaultGroupId).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        receiveFiber <- sqs.consumer.receiveSingleManualDelete(queueUrl, waitTimeSeconds = 5.seconds).start
        _ <- Task.sleep(3.seconds) >> sqs.producer.sendSingleMessage(message1, queueUrl)
        receivedMessages <- receiveFiber.join
        receiveFiberEmpty <- sqs.consumer.receiveSingleManualDelete(queueUrl, waitTimeSeconds = 1.seconds).start
        _ <- Task.sleep(3.seconds) >> sqs.producer.sendSingleMessage(message2, queueUrl)
        receivedMessagesEmpty <- receiveFiberEmpty.join
      } yield {
        receivedMessages.size shouldBe 1
        receivedMessages.map(_.body) shouldBe List(message1.body)
        receivedMessagesEmpty shouldBe List.empty
      }
    }.runSyncUnsafe()
  }

  "receiveSingleAutoDelete" should "consume up to 10 messages at a time" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.listOfN(15, genFifoMessage(defaultGroupId)).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Task.traverse(messages)(sqs.producer.sendSingleMessage(_, queueUrl))
        receivedMessages1 <- sqs.consumer.receiveSingleAutoDelete(queueUrl)
        receivedMessages2 <- sqs.consumer.receiveSingleAutoDelete(queueUrl)
        receivedMessages3 <- sqs.consumer.receiveSingleAutoDelete(queueUrl)
      } yield {
        receivedMessages1.size shouldBe 10
        receivedMessages2.size shouldBe 5
        receivedMessages3.size shouldBe 0
        (receivedMessages1 ++ receivedMessages2 ++ receivedMessages3).map(_.body) should contain theSameElementsAs messages.map(_.body)
      }
    }.runSyncUnsafe()
  }

  it should "avoid consuming the same message multiple times with short `visibilityTimeout`, since messages are already deleted " in {
    val queueName = genFifoQueueName.sample.get
    val message = genFifoMessage(defaultGroupId).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- sqs.producer.sendSingleMessage(message, queueUrl)
        receivedMessage <- sqs.consumer.receiveSingleAutoDeleteInternal(queueUrl, visibilityTimeout = 1.seconds, waitTimeSeconds = 5.seconds)
      } yield {
        receivedMessage.size shouldBe 1
        receivedMessage.map(_.body) shouldBe List(message.body)
      }
    }.runSyncUnsafe()
  }

  it should "wait as indicated in `waitTimeSeconds` until a message arrives" in {
    val queueName = genFifoQueueName.sample.get
    val message1 = genFifoMessage(defaultGroupId).sample.get
    val message2 = genFifoMessage(defaultGroupId).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        receiveFiber <- sqs.consumer.receiveSingleAutoDelete(queueUrl, waitTimeSeconds = 5.seconds).start
        _ <- Task.sleep(3.seconds) >> sqs.producer.sendSingleMessage(message1, queueUrl)
        receivedMessages <- receiveFiber.join
        receiveFiberEmpty <- sqs.consumer.receiveSingleAutoDelete(queueUrl, waitTimeSeconds = 1.seconds).start
        _ <- Task.sleep(3.seconds) >> sqs.producer.sendSingleMessage(message2, queueUrl)
        receivedMessagesEmpty <- receiveFiberEmpty.join
      } yield {
        receivedMessages.size shouldBe 1
        receivedMessages.map(_.body) shouldBe List(message1.body)
        receivedMessagesEmpty shouldBe List.empty
      }
    }.runSyncUnsafe()
  }

  "receiveManualDelete" can "allows to manually trigger deletes" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.choose(10, 150).flatMap(Gen.listOfN(_, genFifoMessage(defaultGroupId))).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Observable.fromIterable(messages).mapEvalF(sqs.producer.sendSingleMessage(_, queueUrl)).completedL
        receivedMessages <- sqs.consumer.receiveManualDelete(queueUrl)
          .mapEvalF(deletable => deletable.deleteFromQueue().as(deletable))
          .bufferTimedAndCounted(5.seconds, messages.size)
          .headL
      } yield {
        receivedMessages.size shouldBe messages.size
        receivedMessages.map(_.body) should contain theSameElementsAs messages.map(_.body)
      }
    }.runSyncUnsafe()
  }

  it should "consume the same message multiple times delete is not triggered" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.choose(1, 5).flatMap(Gen.listOfN(_, genFifoMessage(defaultGroupId))).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Observable.fromIterable(messages).mapEvalF(sqs.producer.sendSingleMessage(_, queueUrl)).completedL
        receivedMessages <- sqs.consumer.receiveManualDelete(queueUrl, visibilityTimeout = 1.seconds)
          .bufferTimed(5.seconds)
          .headL
      } yield {
        receivedMessages.size should be > messages.size
      }
    }.runSyncUnsafe()
  }

  "receiveAutoDelete" can "does not require manually triggering message deletion" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.choose(10, 150).flatMap(Gen.listOfN(_, genFifoMessage(defaultGroupId))).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Observable.fromIterable(messages).mapEvalF(sqs.producer.sendSingleMessage(_, queueUrl)).completedL
        receivedMessages <- sqs.consumer.receiveAutoDelete(queueUrl)
          .bufferTimedAndCounted(10.seconds, messages.size)
          .headL
      } yield {
        receivedMessages.size shouldBe messages.size
        receivedMessages.map(_.body) should contain theSameElementsAs messages.map(_.body)
      }
    }.runSyncUnsafe()
  }

  it should "NOT consume the same message multiple times since it's already been deleted" in {
    val queueName = genFifoQueueName.sample.get
    val messages = Gen.choose(1, 5).flatMap(Gen.listOfN(_, genFifoMessage(defaultGroupId))).sample.get
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = fifoDeduplicationQueueAttr)
        _ <- Observable.fromIterable(messages).mapEvalF(sqs.producer.sendSingleMessage(_, queueUrl)).completedL
        receivedMessages <- sqs.consumer.receiveManualDelete(queueUrl, visibilityTimeout = 1.seconds)
          .bufferTimed(6.seconds)
          .headL
      } yield {
        receivedMessages.size should be > messages.size
      }
    }.runSyncUnsafe()
  }

}