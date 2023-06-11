# ZIO Google Cloud Pub/Sub

[Google Cloud Pub/Sub](https://cloud.google.com/pubsub) client providing stream-based, purely functional API with [ZIO](https://zio.dev) and [ZIO Streams](https://zio.dev/reference/stream).

# Modules
 - `zio-gc-pubsub` Provides shared components/interfaces/models
 - `zio-gc-pubsub-google` Provides subscriber, publisher and admin clients implementations using the [Google Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library
 - `zio-gc-pubsub-serde-circe` Provides Json Serializer/Deserializer using the [circe](https://circe.github.io/circe) codec
 - `zio-gc-pubsub-serde-vulcan` Provides Avro schema Serializer/Deserializer using the [vulcan](https://fd4s.github.io/vulcan) codec

Alternative implementations and codecs may be added later.

## Getting Started

To get started with sbt, add the following line to your build.sbt file to use the implementation with the Google Java library:
```scala
libraryDependencies += "com.anymindgroup" %% "zio-gc-pubsub-google" % zioGcPubsubVersion
```

## Example

Example code that setups a sample topic + subscription and creates a subscription stream with a publisher producing random values in the background (see [examples/google/src/main/scala/MyPubSubApp.scala](examples/google/src/main/scala/MyPubSubApp.scala))
```scala
package examples.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig, Subscriber}
import com.anymindgroup.pubsub.model.{Encoding, SchemaSettings, Topic}
import com.anymindgroup.pubsub.pub.PublishMessage
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.Subscription

import zio.Console.printLine
import zio.stream.ZStream
import zio.{RIO, Random, Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

object MyPubSubApp extends ZIOAppDefault {

  override def run: ZIO[Scope & ZIOAppArgs, Any, Any] = (for {
    consumeAmount      <- ZIOAppArgs.getArgs.map(_.headOption.flatMap(_.toIntOption).getOrElse(10))
    subscriptionStream <- makeSubscriptionStream(consumeAmount)
    _                  <- subscriptionStream.drainFork(samplesPublishStream).runDrain // run publisher in the background
  } yield ()).provideSome(pubsubConnection, examplesSetup)

  private val exampleTopic = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  private val exampleSubscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
  )

  // creates sample topic and subscription if they don't exist yet
  private val examplesSetup = ZLayer.fromZIO(PubsubAdmin.setup(List(exampleTopic), List(exampleSubscription)))

  private def makeSubscriptionStream(amount: Int): RIO[PubsubConnectionConfig & Scope, ZStream[Any, Throwable, Unit]] =
    for {
      connection <- ZIO.service[PubsubConnectionConfig]
      stream     <- Subscriber.makeStreamingPullSubscription(connection, exampleSubscription.name, Serde.int)
    } yield stream.zipWithIndex.mapZIO { case ((message, ackReply), idx) =>
      for {
        _ <-
          printLine(
            s"Received message ${idx + 1} / ${amount} with id ${message.meta.messageId.value} and data ${message.data}"
          )
        _ <- ackReply.ack()
      } yield ()
    }.take(amount.toLong)

  // publish random integer in an interval
  private val samplesPublishStream: ZStream[Scope & PubsubConnectionConfig, Throwable, Unit] = for {
    connection <- ZStream.service[PubsubConnectionConfig]
    publisher <- ZStream.fromZIO(
                   google.Publisher.make(
                     connection = connection,
                     topic = exampleTopic,
                     enableOrdering = false,
                   )
                 )
    _ <- ZStream
           .repeatZIOWithSchedule(
             Random.nextIntBetween(0, Int.MaxValue),
             Schedule.fixed(2.seconds),
           )
           .mapZIO { sampleData =>
             publisher.publishEvent(
               PublishMessage(
                 data = sampleData,
                 attributes = Map.empty,
                 orderingKey = None,
               )
             )
           }
  } yield ()

  private val pubsubConnection = ZLayer.succeed(
    PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085")
  )
}
```

### Running example code 
Start Google Pub/Sub emulator with docker:
```shell
 docker run -p 8085:8085 --rm gcr.io/google.com/cloudsdktool/cloud-sdk:427.0.0-emulators -- gcloud beta emulators pubsub start --project=any --host-port=0.0.0.0:8085
```
or with docker-compose unsing provided docker-compose.yaml
```shell
docker-compose up
```

Run example with sbt:
```shell
sbt 'examplesGoogle/run 5' # will terminate after the subscriber received 5 messages
```