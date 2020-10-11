package monix.connect.aws.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource
import pureconfig._
import pureconfig.generic.auto._
import AwsClientConf._
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsSessionCredentials, DefaultCredentialsProvider, EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider, ProfileCredentialsProvider, StaticCredentialsProvider, SystemPropertyCredentialsProvider}

class AwsCredentialsProviderConfSpec extends AnyFlatSpec with Matchers {

  s"$AwsCredentialsProviderConf" should "allow to set aws default credentials" in {
    //given
    val configSource = ConfigSource.string(
      "" +
        """
          |{
          |  provider: "default"
          |}
          |""".stripMargin)
    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[DefaultCredentialsProvider]
  }

  it should "set aws anonymous credentials" in {
    //given
    val configSource = ConfigSource.string(
      "" +
        """
          |{
          |  provider: "anonymous"
          |}
          |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[AnonymousCredentialsProvider]

  }

  it should "set aws environment credentials" in {
    //given
    val configSource = ConfigSource.string(
      "" +
        """
          |{
          |  provider: "environment"
          |}
          |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[EnvironmentVariableCredentialsProvider]
  }

  it should "set aws instance credentials" in {
    //given
    val configSource = ConfigSource.string(
      "" +
        """
          |{
          |  provider: "instance"
          |}
          |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[InstanceProfileCredentialsProvider]
  }

  it should "set aws profile `default` credentials" in {
    //given
    val configSource = ConfigSource.string(
      "" +
        """
          |{
          |  provider: "profile"
          |}
          |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[ProfileCredentialsProvider]
    credentialsConf.profileName.isDefined shouldBe false
  }

  it should "set aws profile credentials" in {
    //given
    val profileName = "dev"
    val configSource = ConfigSource.string(
      "" +
        s"""
          |{
          |  provider: "profile"
          |  profile-name: "$profileName"
          |}
          |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[ProfileCredentialsProvider]
    credentialsConf.profileName.isDefined shouldBe true
    credentialsConf.profileName.get shouldBe profileName
  }

  it should "set aws static credentials" in {
    //given
    val accessKeyId = "sample-key"
    val secretAccessKey = "sample-secret"
    val configSource = ConfigSource.string(
      "" +
        s"""
          |{
          |  provider: "static"
          |  static-credentials {
          |    access-key-id: "$accessKeyId"
          |    secret-access-key: "$secretAccessKey"
          |  }
          |}
          |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[StaticCredentialsProvider]
    val awsCredentials = credentialsConf.credentialsProvider.resolveCredentials()
    awsCredentials.accessKeyId() shouldBe accessKeyId
    awsCredentials.secretAccessKey() shouldBe secretAccessKey
  }

  it should "set aws static session credentials" in {
    //given
    val accessKeyId = "sample-key"
    val secretAccessKey = "sample-secret"
    val sessionToken = "sample-session-token"
    val configSource = ConfigSource.string(
      "" +
        s"""
           |{
           |  provider: "static"
           |  static-credentials {
           |    access-key-id: "$accessKeyId"
           |    secret-access-key: "$secretAccessKey"
           |    session-token: "$sessionToken"
           |  }
           |}
           |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[StaticCredentialsProvider]
    val staticCreds = credentialsConf.credentialsProvider.asInstanceOf[StaticCredentialsProvider]
    val sessionCreds = staticCreds.resolveCredentials().asInstanceOf[AwsSessionCredentials]
    sessionCreds.sessionToken() shouldBe sessionToken
    sessionCreds.accessKeyId() shouldBe accessKeyId
    sessionCreds.secretAccessKey() shouldBe secretAccessKey
  }

  it should "set aws system credentials" in {
    //given
    val configSource = ConfigSource.string(
      "" +
        s"""
           |{
           |  provider: "system"
           |}
           |""".stripMargin)

    //when
    val credentialsConf = configSource.loadOrThrow[AwsCredentialsProviderConf]

    //then
    credentialsConf.credentialsProvider shouldBe a[SystemPropertyCredentialsProvider]
  }
}