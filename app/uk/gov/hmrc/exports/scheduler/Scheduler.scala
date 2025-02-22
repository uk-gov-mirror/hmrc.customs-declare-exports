/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.exports.scheduler

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Cancellable}
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.exports.config.AppConfig
import uk.gov.hmrc.exports.scheduler.jobs.ScheduledJob

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Scheduler @Inject()(
  actorSystem: ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  appConfig: AppConfig,
  schedulerDateUtil: SchedulerDateUtil,
  scheduledJobs: ScheduledJobs
)(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  val runningJobs: Iterable[Cancellable] = scheduledJobs.jobs.map { job =>
    logger.info(
      s"Scheduling job [${job.name}] to run periodically at [${job.firstRunTime}] with interval [${job.interval.length} ${job.interval.unit}]"
    )
    actorSystem.scheduler.schedule(
      durationUntil(nextRunDateFor(job)),
      job.interval,
      new Runnable() {
        override def run(): Unit =
          job.execute().map { _ =>
            logger.info(s"Scheduled Job [${job.name}]: Completed Successfully")
          } recover {
            case t: Throwable =>
              logger.error(s"Scheduled Job [${job.name}]: Failed", t)
          }
      }
    )
  }

  applicationLifecycle.addStopHook(() => Future.successful(runningJobs.foreach(_.cancel())))

  private def nextRunDateFor(job: ScheduledJob): Instant =
    schedulerDateUtil.nextRun(job.firstRunTime, job.interval)

  private def durationUntil(datetime: Instant): FiniteDuration = {
    val now = Instant.now(appConfig.clock)

    if (datetime.isBefore(now))
      throw new IllegalArgumentException(s"Expected a future or present datetime but was [$datetime]")
    else FiniteDuration(now.until(datetime, ChronoUnit.SECONDS), TimeUnit.SECONDS)
  }
}
