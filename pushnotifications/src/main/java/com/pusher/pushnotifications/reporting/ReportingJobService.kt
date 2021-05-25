package com.pusher.pushnotifications.reporting

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.firebase.jobdispatcher.JobParameters
import com.firebase.jobdispatcher.JobService
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.annotations.SerializedName
import com.pusher.pushnotifications.api.OperationCallbackNoArgs
import com.pusher.pushnotifications.logging.Logger
import com.pusher.pushnotifications.reporting.api.*
import kotlinx.coroutines.CoroutineScope

data class PusherMetadata(
        val instanceId: String,
        val publishId: String,
        val clickAction: String?,
        @SerializedName("hasDisplayableContent") private val _hasDisplayableContent: Boolean?,
        @SerializedName("hasData") private val _hasData: Boolean?
) {
  val hasDisplayableContent: Boolean
    get() = _hasDisplayableContent ?: false

  val hasData: Boolean
    get() = _hasData ?: false
}

class ReportingWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
  companion object {
    private const val BUNDLE_EVENT_TYPE_KEY = "ReportEventType"
    private const val BUNDLE_INSTANCE_ID_KEY = "InstanceId"
    private const val BUNDLE_DEVICE_ID_KEY = "DeviceId"
    private const val BUNDLE_USER_ID_KEY = "UserId"
    private const val BUNDLE_PUBLISH_ID_KEY = "PublishId"
    private const val BUNDLE_TIMESTAMP_KEY = "Timestamp"
    private const val BUNDLE_APP_IN_BACKGROUND_KEY = "AppInBackground"
    private const val BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY = "HasDisplayableContent"
    private const val BUNDLE_HAS_DATA_KEY = "HasData"

    fun toBundle(reportEvent: ReportEvent): Bundle {
      val b = Bundle()
      when (reportEvent) {
        is DeliveryEvent -> {
          b.putString(BUNDLE_EVENT_TYPE_KEY, reportEvent.event.toString())
          b.putString(BUNDLE_INSTANCE_ID_KEY, reportEvent.instanceId)
          b.putString(BUNDLE_DEVICE_ID_KEY, reportEvent.deviceId)
          b.putString(BUNDLE_USER_ID_KEY, reportEvent.userId)
          b.putString(BUNDLE_PUBLISH_ID_KEY, reportEvent.publishId)
          b.putLong(BUNDLE_TIMESTAMP_KEY, reportEvent.timestampSecs)
          b.putBoolean(BUNDLE_APP_IN_BACKGROUND_KEY, reportEvent.appInBackground!!)
          b.putBoolean(BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY, reportEvent.hasDisplayableContent!!)
          b.putBoolean(BUNDLE_HAS_DATA_KEY, reportEvent.hasData!!)
        }

        is OpenEvent -> {
          b.putString(BUNDLE_EVENT_TYPE_KEY, reportEvent.event.toString())
          b.putString(BUNDLE_INSTANCE_ID_KEY, reportEvent.instanceId)
          b.putString(BUNDLE_DEVICE_ID_KEY, reportEvent.deviceId)
          b.putString(BUNDLE_USER_ID_KEY, reportEvent.userId)
          b.putString(BUNDLE_PUBLISH_ID_KEY, reportEvent.publishId)
          b.putLong(BUNDLE_TIMESTAMP_KEY, reportEvent.timestampSecs)
        }
      }

      return b
    }

    private class MissingInstanceIdException : RuntimeException()
    private class MissingEventTypeException : java.lang.RuntimeException()

    @Throws(MissingInstanceIdException::class, MissingEventTypeException::class)
    private fun fromData(data: Data): ReportEvent {
      val instanceId: String? = data.getString(BUNDLE_INSTANCE_ID_KEY)
      @Suppress("FoldInitializerAndIfToElvis")
      if (instanceId == null) {
        // It's possible that we are processing a bundle that was created with an old SDK
        // version that didn't had this key. Our migration strategy is to drop the reporting
        // as it's (a) a rare one-time transition and (b) it's a best effort feature.
        // Throwing a specific exception (to not compromise the code design -- nullable return
        // type) which will be caught on calling this private fun.
        throw MissingInstanceIdException()
      }

      val event = data.getString(BUNDLE_EVENT_TYPE_KEY)?.let { ReportEventType.valueOf(it) }
              ?: throw MissingEventTypeException()

      when (event) {
        ReportEventType.Delivery -> {
          return DeliveryEvent(
                  instanceId = instanceId,
                  deviceId = data.getString(BUNDLE_DEVICE_ID_KEY) ?: "",
                  userId = data.getString(BUNDLE_USER_ID_KEY),
                  publishId = data.getString(BUNDLE_PUBLISH_ID_KEY) ?: "",
                  timestampSecs = data.getLong(BUNDLE_TIMESTAMP_KEY, 0L),
                  appInBackground = data.getBoolean(BUNDLE_APP_IN_BACKGROUND_KEY, false),
                  hasDisplayableContent = data.getBoolean(BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY, false),
                  hasData = data.getBoolean(BUNDLE_HAS_DATA_KEY, false)
          )
        }
        ReportEventType.Open -> {
          return OpenEvent(
                  instanceId = instanceId,
                  deviceId = data.getString(BUNDLE_DEVICE_ID_KEY) ?: "",
                  userId = data.getString(BUNDLE_USER_ID_KEY),
                  publishId = data.getString(BUNDLE_PUBLISH_ID_KEY) ?: "",
                  timestampSecs = data.getLong(BUNDLE_TIMESTAMP_KEY, 0L)
          )
        }
      }
    }

    fun toInputData(reportEvent: ReportEvent): Data {
      val d = Data.Builder()
      when (reportEvent) {
        is DeliveryEvent -> {
          d.putString(BUNDLE_EVENT_TYPE_KEY, reportEvent.event.toString())
                  .putString(BUNDLE_INSTANCE_ID_KEY, reportEvent.instanceId)
                  .putString(BUNDLE_DEVICE_ID_KEY, reportEvent.deviceId)
                  .putString(BUNDLE_USER_ID_KEY, reportEvent.userId)
                  .putString(BUNDLE_PUBLISH_ID_KEY, reportEvent.publishId)
                  .putLong(BUNDLE_TIMESTAMP_KEY, reportEvent.timestampSecs)
                  .putBoolean(BUNDLE_APP_IN_BACKGROUND_KEY, reportEvent.appInBackground!!)
                  .putBoolean(BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY, reportEvent.hasDisplayableContent!!)
                  .putBoolean(BUNDLE_HAS_DATA_KEY, reportEvent.hasData!!)
        }

        is OpenEvent -> {
          d.putString(BUNDLE_EVENT_TYPE_KEY, reportEvent.event.toString())
                  .putString(BUNDLE_INSTANCE_ID_KEY, reportEvent.instanceId)
                  .putString(BUNDLE_DEVICE_ID_KEY, reportEvent.deviceId)
                  .putString(BUNDLE_USER_ID_KEY, reportEvent.userId)
                  .putString(BUNDLE_PUBLISH_ID_KEY, reportEvent.publishId)
                  .putLong(BUNDLE_TIMESTAMP_KEY, reportEvent.timestampSecs)
        }
      }
      return d.build()
    }
  }

  private val log = Logger.get(this::class)
  override fun startWork(): ListenableFuture<Result> {
    return CallbackToFutureAdapter.getFuture { completer ->
      try {
        Log.e("WORKER", String.format("*********INPUT DATA: %s", inputData.toString()))
        val reportEvent = fromData(inputData)
        Log.e("WORKER", String.format("*********Event DATA: %s", reportEvent.toString()))
        reportEvent.deviceId

        ReportingAPI(reportEvent.instanceId).submit(
                reportEvent = reportEvent,
                operationCallback = object : OperationCallbackNoArgs {
                  override fun onSuccess() {
                    log.v("Successfully submitted report.")
                    Log.e("WORKER", "Successfully submitted report.")
                    completer.set(Result.success(inputData))
                  }

                  override fun onFailure(t: Throwable) {
                    log.w("Failed submitted report.", t)
                    Log.e("WORKER", String.format("Successfully submitted report. %s", t))
                    if (t !is UnrecoverableRuntimeException) {
                      completer.set(Result.retry())
                    } else {
                      completer.set(Result.failure(inputData))
                    }
                  }
                }
        )
      } catch (e: MissingInstanceIdException) {
        log.w("Missing instance id, can't report.")
        Log.e("WORKER", "Missing instance id, can't report.")
        completer.set(Result.failure())
      } catch (e: MissingEventTypeException) {
        log.w("Missing event type, can't report.")
        Log.e("WORKER", "Missing event type, can't report.")
        completer.set(Result.failure())
      }
    }

  }

  override fun onStopped() {
    super.onStopped()
  }
}

class ReportingJobService : JobService() {
  companion object {
    private const val BUNDLE_EVENT_TYPE_KEY = "ReportEventType"
    private const val BUNDLE_INSTANCE_ID_KEY = "InstanceId"
    private const val BUNDLE_DEVICE_ID_KEY = "DeviceId"
    private const val BUNDLE_USER_ID_KEY = "UserId"
    private const val BUNDLE_PUBLISH_ID_KEY = "PublishId"
    private const val BUNDLE_TIMESTAMP_KEY = "Timestamp"
    private const val BUNDLE_APP_IN_BACKGROUND_KEY = "AppInBackground"
    private const val BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY = "HasDisplayableContent"
    private const val BUNDLE_HAS_DATA_KEY = "HasData"

    fun toBundle(reportEvent: ReportEvent): Bundle {
      val b = Bundle()
      when (reportEvent) {
        is DeliveryEvent -> {
          b.putString(BUNDLE_EVENT_TYPE_KEY, reportEvent.event.toString())
          b.putString(BUNDLE_INSTANCE_ID_KEY, reportEvent.instanceId)
          b.putString(BUNDLE_DEVICE_ID_KEY, reportEvent.deviceId)
          b.putString(BUNDLE_USER_ID_KEY, reportEvent.userId)
          b.putString(BUNDLE_PUBLISH_ID_KEY, reportEvent.publishId)
          b.putLong(BUNDLE_TIMESTAMP_KEY, reportEvent.timestampSecs)
          b.putBoolean(BUNDLE_APP_IN_BACKGROUND_KEY, reportEvent.appInBackground!!)
          b.putBoolean(BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY, reportEvent.hasDisplayableContent!!)
          b.putBoolean(BUNDLE_HAS_DATA_KEY, reportEvent.hasData!!)
        }

        is OpenEvent -> {
          b.putString(BUNDLE_EVENT_TYPE_KEY, reportEvent.event.toString())
          b.putString(BUNDLE_INSTANCE_ID_KEY, reportEvent.instanceId)
          b.putString(BUNDLE_DEVICE_ID_KEY, reportEvent.deviceId)
          b.putString(BUNDLE_USER_ID_KEY, reportEvent.userId)
          b.putString(BUNDLE_PUBLISH_ID_KEY, reportEvent.publishId)
          b.putLong(BUNDLE_TIMESTAMP_KEY, reportEvent.timestampSecs)
        }
      }

      return b
    }

    private class MissingInstanceIdException : RuntimeException()

    @Throws(MissingInstanceIdException::class)
    private fun fromBundle(bundle: Bundle): ReportEvent {
      val instanceId : String? = bundle.getString(BUNDLE_INSTANCE_ID_KEY)
      @Suppress("FoldInitializerAndIfToElvis")
      if (instanceId == null) {
        // It's possible that we are processing a bundle that was created with an old SDK
        // version that didn't had this key. Our migration strategy is to drop the reporting
        // as it's (a) a rare one-time transition and (b) it's a best effort feature.
        // Throwing a specific exception (to not compromise the code design -- nullable return
        // type) which will be caught on calling this private fun.
        throw MissingInstanceIdException()
      }

      val event = ReportEventType.valueOf(bundle.getString(BUNDLE_EVENT_TYPE_KEY))
      when (event) {
        ReportEventType.Delivery -> {
          return DeliveryEvent(
                  instanceId = instanceId,
                  deviceId = bundle.getString(BUNDLE_DEVICE_ID_KEY),
                  userId = bundle.getString(BUNDLE_USER_ID_KEY),
                  publishId = bundle.getString(BUNDLE_PUBLISH_ID_KEY),
                  timestampSecs = bundle.getLong(BUNDLE_TIMESTAMP_KEY),
                  appInBackground = bundle.getBoolean(BUNDLE_APP_IN_BACKGROUND_KEY),
                  hasDisplayableContent = bundle.getBoolean(BUNDLE_HAS_DISPLAYABLE_CONTENT_KEY),
                  hasData = bundle.getBoolean(BUNDLE_HAS_DATA_KEY)
          )
        }
        ReportEventType.Open -> {
          return OpenEvent(
                  instanceId = instanceId,
                  deviceId = bundle.getString(BUNDLE_DEVICE_ID_KEY),
                  userId = bundle.getString(BUNDLE_USER_ID_KEY),
                  publishId = bundle.getString(BUNDLE_PUBLISH_ID_KEY),
                  timestampSecs = bundle.getLong(BUNDLE_TIMESTAMP_KEY)
          )
        }
      }
    }
  }

  private val log = Logger.get(this::class)

  override fun onStartJob(params: JobParameters?): Boolean {
    params?.let {
      val extras = it.extras

      if (extras != null) {
        try {
          val reportEvent = fromBundle(extras)
          reportEvent.deviceId

          ReportingAPI(reportEvent.instanceId).submit(
            reportEvent = reportEvent,
            operationCallback = object: OperationCallbackNoArgs {
              override fun onSuccess() {
                log.v("Successfully submitted report.")
                jobFinished(params, false)
              }

              override fun onFailure(t: Throwable) {
                log.w("Failed submitted report.", t)
                val shouldRetry = t !is UnrecoverableRuntimeException

                jobFinished(params, shouldRetry)
              }
            }
          )
        } catch (e : MissingInstanceIdException) {
          log.w("Missing instance id, can't report.")
          return false
        }
      } else {
        log.w("Incorrect start of service: extras bundle is missing.")
        return false
      }
    }

    return true // A background job was started
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    return true // Answers the question: "Should this job be retried?"
  }
}
