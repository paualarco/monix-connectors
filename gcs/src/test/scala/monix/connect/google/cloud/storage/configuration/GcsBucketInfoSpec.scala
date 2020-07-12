package monix.connect.google.cloud.storage.configuration

import com.google.cloud.ReadChannel
import com.google.cloud.storage.{Bucket, BucketInfo, Storage, Option => _}
import monix.connect.google.cloud.storage.GscFixture
import org.mockito.IdiomaticMockito
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.jdk.CollectionConverters._

class GcsBucketInfoSpec extends AnyWordSpecLike with IdiomaticMockito with Matchers with GscFixture {

  val underlying: Bucket = mock[Bucket]
  val mockStorage: Storage = mock[Storage]
  val readChannel: ReadChannel = mock[ReadChannel]

  s"${GcsBucketInfo}" can {

    "be created from default java BlobInfo" in {
      //given
      val bucketName = "sampleBucket"
      val bucketInfo: BucketInfo = BucketInfo.newBuilder(bucketName).build()

      //when
      val gcsBlobInfo: GcsBucketInfo = GcsBucketInfo.fromJava(bucketInfo)

      //then
      assertEqualBlobFields(bucketInfo, gcsBlobInfo)
    }

    "be created from method `withMetadata`" in   {
      //given
      val bucketName = Gen.alphaLowerStr.sample.get
      val location = GcsBucketInfo.Locations.`ASIA-EAST1`
      val metadata = genBucketInfoMetadata.sample.get

      //when
      val bucketInfo: BucketInfo = GcsBucketInfo.withMetadata(bucketName, location, Some(metadata))

      //then
      Option(bucketInfo.getStorageClass) shouldBe metadata.storageClass
      Option(bucketInfo.getLogging) shouldBe metadata.logging
      Option(bucketInfo.getRetentionPeriod) shouldBe metadata.retentionPeriod.map(_.toMillis)
      Option(bucketInfo.versioningEnabled) shouldBe metadata.versioningEnabled
      Option(bucketInfo.requesterPays) shouldBe metadata.requesterPays
      Option(bucketInfo.getDefaultEventBasedHold) shouldBe metadata.defaultEventBasedHold
      bucketInfo.getAcl shouldBe metadata.acl.asJava
      bucketInfo.getDefaultAcl shouldBe metadata.defaultAcl.asJava
      bucketInfo.getCors shouldBe metadata.cors.asJava
      bucketInfo.getLifecycleRules shouldBe metadata.lifecycleRules.asJava
      Option(bucketInfo.getIamConfiguration) shouldBe metadata.iamConfiguration
      Option(bucketInfo.getDefaultKmsKeyName) shouldBe metadata.defaultKmsKeyName
      bucketInfo.getLabels shouldBe metadata.labels.asJava
      Option(bucketInfo.getIndexPage) shouldBe metadata.indexPage
      Option(bucketInfo.getNotFoundPage) shouldBe metadata.notFoundPage
    }
  }

  def assertEqualBlobFields(bucketInfo: BucketInfo, gcsBucketInfo: GcsBucketInfo): Assertion = {
    bucketInfo.getName shouldBe gcsBucketInfo.name
    bucketInfo.getLocation shouldBe gcsBucketInfo.location
    bucketInfo.getOwner shouldBe gcsBucketInfo.owner
    bucketInfo.getSelfLink shouldBe gcsBucketInfo.selfLink
    Option(bucketInfo.requesterPays) shouldBe gcsBucketInfo.requesterPays
    Option(bucketInfo.versioningEnabled) shouldBe gcsBucketInfo.versioningEnabled
    bucketInfo.getIndexPage shouldBe gcsBucketInfo.indexPage
    bucketInfo.getNotFoundPage shouldBe gcsBucketInfo.notFoundPage
    Option(bucketInfo.getLifecycleRules).getOrElse(List.empty.asJava) shouldBe gcsBucketInfo.lifecycleRules.asJava
    Option(bucketInfo.getStorageClass) shouldBe gcsBucketInfo.storageClass
    bucketInfo.getEtag shouldBe gcsBucketInfo.etag
    Option(bucketInfo.getMetageneration) shouldBe gcsBucketInfo.metageneration
    Option(bucketInfo.getCors).getOrElse(List.empty.asJava) shouldBe gcsBucketInfo.cors.asJava
    Option(bucketInfo.getAcl).getOrElse(List.empty.asJava) shouldBe gcsBucketInfo.acl.asJava
    Option(bucketInfo.getDefaultAcl).getOrElse(List.empty.asJava) shouldBe gcsBucketInfo.defaultAcl.asJava
    Option(bucketInfo.getLabels).getOrElse(Map.empty.asJava) shouldBe gcsBucketInfo.labels.asJava
    bucketInfo.getDefaultKmsKeyName shouldBe gcsBucketInfo.defaultKmsKeyName
    Option(bucketInfo.getDefaultEventBasedHold) shouldBe gcsBucketInfo.defaultEventBasedHold
    Option(bucketInfo.getRetentionEffectiveTime) shouldBe gcsBucketInfo.retentionEffectiveTime
    Option(bucketInfo.retentionPolicyIsLocked) shouldBe gcsBucketInfo.retentionPolicyIsLocked
    Option(bucketInfo.getRetentionPeriod) shouldBe gcsBucketInfo.retentionPeriod
    bucketInfo.getIamConfiguration shouldBe gcsBucketInfo.iamConfiguration
    bucketInfo.getLocationType shouldBe gcsBucketInfo.locationType
    bucketInfo.getGeneratedId shouldBe gcsBucketInfo.generatedId
  }

}
