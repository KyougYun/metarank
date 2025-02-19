package ai.metarank.fstore.redis

import ai.metarank.config.StateStoreConfig.{RedisCredentials, RedisTLS, RedisTimeouts}
import ai.metarank.config.StateStoreConfig.RedisStateConfig.{CacheConfig, DBConfig, PipelineConfig}
import ai.metarank.fstore.Persistence
import ai.metarank.fstore.Persistence.{KVCodec, ModelName, ModelStore}
import ai.metarank.fstore.cache.{CachedClickthroughStore, CachedKVStore, CachedModelStore}
import ai.metarank.fstore.codec.StoreFormat
import ai.metarank.fstore.memory.MemModelStore

import ai.metarank.fstore.redis.client.RedisClient
import ai.metarank.ml.Model
import ai.metarank.model.{FeatureValue, Key, Schema}
import ai.metarank.model.{FeatureKey, FeatureValue, Key, Schema}
import ai.metarank.util.Logging
import cats.effect.IO
import cats.effect.kernel.Resource
import com.github.blemale.scaffeine.Scaffeine
import io.lettuce.core.TrackingArgs
import io.lettuce.core.api.push.{PushListener, PushMessage}

import java.nio.ByteBuffer
import java.util
import scala.jdk.CollectionConverters._
import shapeless.syntax.typeable._

import scala.concurrent.duration._

case class RedisPersistence(
    schema: Schema,
    stateClient: RedisClient,
    modelClient: RedisClient,
    valuesClient: RedisClient,
//    rankingsClient: RedisClient,
    cache: CacheConfig,
    format: StoreFormat
) extends Persistence
    with Logging {
  import RedisPersistence._

  stateClient.readerConn.addListener(new PushListener {
    override def onPushMessage(message: PushMessage): Unit = if (message.getType == "invalidate") {
      val content = message.getContent()
      if (content.size() >= 2) {
        val payloads = content.get(1).asInstanceOf[util.ArrayList[ByteBuffer]]
        if ((payloads != null) && !payloads.isEmpty) {
          payloads.asScala.foreach(bytes => {
            val keyRaw    = new String(bytes.array())
            val keyString = keyRaw.substring(2)
            val keyType   = keyRaw.substring(0, 1)
            invalidate(keyType, keyString)
            // logger.debug(s"cache invalidation message: key=$keyString type=$keyType")
          })
        } else {
          // logger.debug("empty invalidation message")
        }
      }

    }

    def invalidate(keyType: String, keyString: String) = keyType match {
      case Prefix.STATE  => Key.fromString(keyString).foreach(stateCache.invalidate)
      case Prefix.VALUES => // no caching
      case Prefix.MODELS => modelCache.invalidate(ModelName(keyString))
      case Prefix.CT     => // no caching
      case _             => logger.warn(s"cannot handle invalidation of key=${keyString}")
    }

  })

  lazy val stateCache = Scaffeine()
    .ticker(ticker)
    .maximumSize(cache.maxSize)
    .softValues()
    .expireAfterAccess(cache.ttl)
    .build[Key, AnyRef]()

  lazy val modelCache = Scaffeine()
    .ticker(ticker)
    .maximumSize(32)
    .expireAfterAccess(1.hour)
    .build[ModelName, Model[_]]()

  override lazy val lists: Map[FeatureKey, RedisBoundedListFeature] = schema.lists.map { case (name, conf) =>
    name -> RedisBoundedListFeature(conf, stateClient, Prefix.STATE, format)
  }

  override lazy val counters: Map[FeatureKey, RedisCounterFeature] = schema.counters.map { case (name, conf) =>
    name -> RedisCounterFeature(conf, stateClient, Prefix.STATE, format)
  }
  override lazy val periodicCounters: Map[FeatureKey, RedisPeriodicCounterFeature] =
    schema.periodicCounters.map { case (name, conf) =>
      name -> RedisPeriodicCounterFeature(conf, stateClient, Prefix.STATE, format)
    }

  override lazy val freqs: Map[FeatureKey, RedisFreqEstimatorFeature] = schema.freqs.map { case (name, conf) =>
    name -> RedisFreqEstimatorFeature(conf, stateClient, Prefix.STATE, format)
  }

  override lazy val scalars: Map[FeatureKey, RedisScalarFeature] = schema.scalars.map { case (name, conf) =>
    name -> RedisScalarFeature(conf, stateClient, Prefix.STATE, format)
  }
  override lazy val stats: Map[FeatureKey, RedisStatsEstimatorFeature] = schema.stats.map { case (name, conf) =>
    name -> RedisStatsEstimatorFeature(conf, stateClient, Prefix.STATE, format)
  }

  override lazy val maps: Map[FeatureKey, RedisMapFeature] = schema.maps.map { case (name, conf) =>
    name -> RedisMapFeature(conf, stateClient, Prefix.STATE, format)
  }

  override lazy val models: ModelStore = CachedModelStore(
    fast = MemModelStore(modelCache),
    slow = RedisModelStore(modelClient, Prefix.MODELS)(format.modelName, format.model)
  )

  override lazy val values: RedisKVStore[Key, FeatureValue] =
    RedisKVStore(valuesClient, Prefix.VALUES)(format.key, format.featureValue)

//  override lazy val cts: Persistence.ClickthroughStore = RedisClickthroughStore(rankingsClient, Prefix.CT, format)

  override def healthcheck(): IO[Unit] =
    stateClient.ping().void

  override def sync: IO[Unit] = for {
    start <- IO(System.currentTimeMillis())
    _     <- stateClient.doFlush(stateClient.writer.ping().toCompletableFuture)
    _     <- valuesClient.doFlush(valuesClient.writer.ping().toCompletableFuture)
//    _     <- rankingsClient.doFlush(rankingsClient.writer.ping().toCompletableFuture)
    _ <- modelClient.doFlush(modelClient.writer.ping().toCompletableFuture)
  } yield {
    logger.info(s"redis pipeline flushed, took ${System.currentTimeMillis() - start}ms")
  }
}

object RedisPersistence {
  object Prefix {
    val STATE  = "s"
    val VALUES = "v"
    val MODELS = "m"
    val CT     = "c"
  }
  def create(
      schema: Schema,
      host: String,
      port: Int,
      db: DBConfig,
      cache: CacheConfig,
      pipeline: PipelineConfig,
      format: StoreFormat,
      auth: Option[RedisCredentials],
      tls: Option[RedisTLS],
      timeout: RedisTimeouts
  ): Resource[IO, RedisPersistence] = for {
    state  <- RedisClient.create(host, port, db.state, pipeline, auth, tls, timeout)
    models <- RedisClient.create(host, port, db.models, pipeline.copy(enabled = false), auth, tls, timeout)
    values <- RedisClient.create(host, port, db.values, pipeline, auth, tls, timeout)
    _ <- Resource.liftK(
      IO.whenA(cache.maxSize > 0)(
        IO.fromCompletableFuture(
          IO(
            state.reader
              .clientTracking(
                TrackingArgs.Builder
                  .enabled()
                  .bcast()
                  .noloop()
                  .prefixes(Prefix.STATE, Prefix.VALUES, Prefix.MODELS, Prefix.CT)
              )
              .toCompletableFuture
          )
        ).void
      )
    )
  } yield {
    RedisPersistence(schema, state, models, values, cache, format)
  }

}
