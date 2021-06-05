package monix.connect.sqs

import monix.connect.sqs.domain.{QueueName, QueueUrl}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.sqs.model.{QueueAttributeName, QueueDoesNotExistException}

class OperatorSuite extends AnyFlatSpecLike with Matchers with BeforeAndAfterEach with SqsITFixture {

  s"Sqs" can "create queue and check it exists" in {
    Sqs.fromConfig.use { sqs =>
      for {
        existedBefore <- sqs.operator.existsQueue(queueName)
        _ <- sqs.operator.createQueue(queueName)
        existsAfter <- sqs.operator.existsQueue(queueName)
      } yield {
        existedBefore shouldBe false
        existsAfter shouldBe true
      }
    }.runSyncUnsafe()
  }

  it can "create tags, add new ones and list them" in {
    Sqs.fromConfig.use { sqs =>
      val initialTags = Map("mode" -> "difficult")
      val tagsByUrl = Map("environment" -> "test")

      for {
        queueUrl <- sqs.operator.createQueue(queueName, tags = initialTags)
        _ <- sqs.operator.tagQueue(queueUrl, tags = tagsByUrl) //tagging by url
        errorMessage <- sqs.operator.tagQueue(QueueUrl("nonExistingQueue"), tags = tagsByUrl)
          .as(None).onErrorHandle(ex => Some(ex.getMessage))
        appliedTags <- sqs.operator.listQueueTags(queueUrl)
      } yield {
        appliedTags shouldBe initialTags ++ tagsByUrl
        errorMessage.isDefined shouldBe true
        errorMessage shouldBe Some(nonExistingQueueErrorMsg)
      }
    }.runSyncUnsafe()
  }

  it can "remove tags from a queue" in {
    Sqs.fromConfig.use { sqs =>
      val queueType = "type"
      val modeTagKey = "mode"
      val environmentTagKey = "environment"
      val dummyTagKey = "dummy"
      val initialTags = Map(queueType -> "1", modeTagKey -> "difficult",
        environmentTagKey -> "test", dummyTagKey -> "123")
      for {
        queueUrl <- sqs.operator.createQueue(queueName, tags = initialTags)
        untagByUrl <- sqs.operator.untagQueue(queueUrl, tagKeys = List(queueType)) >>
          sqs.operator.listQueueTags(queueUrl)
        untagByName <- sqs.operator.untagQueue(queueUrl, tagKeys = List(modeTagKey, environmentTagKey)) >> //tagging by queue name
          sqs.operator.listQueueTags(queueUrl)
        errorMessage <- sqs.operator.untagQueue(QueueUrl("nonExistingQueue"), tagKeys = List(dummyTagKey))
          .as(None).onErrorHandle(ex => Some(ex.getMessage))
      } yield {
        untagByUrl shouldBe initialTags.filterNot(kv => kv._1 == queueType)
        untagByName shouldBe Map(dummyTagKey -> "123")
        errorMessage.isDefined shouldBe true
        errorMessage shouldBe Some(nonExistingQueueErrorMsg)
      }
    }.runSyncUnsafe()
  }

  it can "create attributes, add new ones and list them" in {
    Sqs.fromConfig.use { sqs =>
      val initialAttributes = Map(QueueAttributeName.DELAY_SECONDS -> "60")
      val attributesByUrl =  Map(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS -> "12345")

      for {
        queueUrl <- sqs.operator.createQueue(queueName, attributes = initialAttributes)
        _ <- sqs.operator.setQueueAttributes(queueUrl, attributes = attributesByUrl)
        errorMessage <- sqs.operator.setQueueAttributes(QueueUrl("randomUrl"), attributes = attributesByUrl)
          .as(None).onErrorHandle(ex => Some(ex.getMessage))
        attributes <- sqs.operator.getQueueAttributes(queueUrl)
      } yield {
        val expectedAttributes = initialAttributes ++ attributesByUrl
        attributes.filter(kv => expectedAttributes.keys.toList.contains(kv._1)) should contain theSameElementsAs expectedAttributes
        errorMessage.isDefined shouldBe true
        errorMessage shouldBe Some(nonExistingQueueErrorMsg)
      }
    }.runSyncUnsafe()
  }

  it can "get a queue and queue url from its name" in {
    Sqs.fromConfig.use { case Sqs(_, _, operator) =>
      for {
        createdQueueUrl <- operator.createQueue(queueName)
        queueUrl <- operator.getQueueUrl(queueName)
        emptyQueueUrl <- operator.getQueueUrl(QueueName("randomName123")).onErrorHandleWith { ex =>
          if (ex.isInstanceOf[QueueDoesNotExistException]) {
            Task.now(Option.empty)
          } else {
            Task.raiseError(ex)
          }
        }
      } yield {
        createdQueueUrl shouldBe QueueUrl(queueUrlPrefix(queueName.name))
        queueUrl shouldBe QueueUrl(queueUrlPrefix(queueName.name))
        emptyQueueUrl shouldBe None
      }
    }.runSyncUnsafe()
  }

  it can "delete queue by url" in {
    Sqs.fromConfig.use { sqs =>
      for {
        queueUrl <- sqs.operator.createQueue(queueName)
        existsBefore <- sqs.operator.existsQueue(queueName)
        existsAfterDelete <- sqs.operator.deleteQueue(queueUrl) >> sqs.operator.existsQueue(queueName)
        errorMessage <- sqs.operator.deleteQueue(queueUrl).as(None).onErrorHandle(ex => Some(ex.getMessage))
      } yield {
        existsBefore shouldBe true
        existsAfterDelete shouldBe false
        errorMessage.isDefined shouldBe true
        errorMessage.get.contains("AWS.SimpleQueueService.NonExistentQueue") shouldBe true
      }
    }.runSyncUnsafe()
  }

  it can "list all queue urls" in {
    val queueNames = Gen.listOfN(10, genQueueName).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        queueUrls <- Task.traverse(queueNames)(sqs.operator.createQueue(_))
        fullQueueList <- sqs.operator.listQueueUrls().toListL
      } yield {
        fullQueueList.size shouldBe queueNames.size
        fullQueueList should contain theSameElementsAs queueUrls
      }
    }.runSyncUnsafe()
  }

  it can "list queue by the name prefix name" in {
    val prefix = Gen.identifier.map(id => s"test-${id.take(5)}").sample.get
    val nonPrefixedQueueNames = Gen.listOfN(10, genQueueName).sample.get
    val prefixedQueueNames = Gen.listOfN(6, genQueueName.map(_.map(prefix + _))).sample.get

    Sqs.fromConfig.use { sqs =>
      for {
        _ <- Task.traverse(nonPrefixedQueueNames)(sqs.operator.createQueue(_))
        prefixedQueueUrls <- Task.traverse(prefixedQueueNames)(sqs.operator.createQueue(_))
        resultList <- sqs.operator.listQueueUrls(Some(prefix)).toListL
      } yield {
        resultList.size shouldBe prefixedQueueNames.size
        resultList should contain theSameElementsAs prefixedQueueUrls
      }
    }.runSyncUnsafe()
  }

}