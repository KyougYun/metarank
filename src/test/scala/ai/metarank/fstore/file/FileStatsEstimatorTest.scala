package ai.metarank.fstore.file

import ai.metarank.fstore.StatsEstimatorSuite
import ai.metarank.fstore.codec.StoreFormat.BinaryStoreFormat
import ai.metarank.model.Feature.StatsEstimatorFeature.StatsEstimatorConfig
import ai.metarank.model.Key.FeatureName
import ai.metarank.model.State.StatsEstimatorState
import ai.metarank.model.Write.PutStatSample
import ai.metarank.util.TestKey
import cats.effect.unsafe.implicits.global
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

import scala.util.Random

class FileStatsEstimatorTest extends StatsEstimatorSuite with FileTest with Eventually with IntegrationPatience {
  override def feature(config: StatsEstimatorConfig): FileStatsEstimatorFeature =
    FileStatsEstimatorFeature(config, db, "x", BinaryStoreFormat)

  // FIXME: may be flaky due to reservoir sampling in the FSE implementation
  it should "pull state" ignore {
    eventually {
      val c = config.copy(name = FeatureName("sss"))
      val f = FileStatsEstimatorFeature(config, db, "x" + Random.nextInt(Int.MaxValue), BinaryStoreFormat)
      f.put(PutStatSample(TestKey(c, "a"), now, 1.0)).unsafeRunSync()
      f.put(PutStatSample(TestKey(c, "a"), now, 1.0)).unsafeRunSync()
      f.put(PutStatSample(TestKey(c, "a"), now, 1.0)).unsafeRunSync()
      val state = FileStatsEstimatorFeature.statsSource.source(f).compile.toList.unsafeRunSync()
      state shouldBe List(StatsEstimatorState(TestKey(c, "a"), Array(1.0, 1.0, 1.0)))
    }
  }
}
