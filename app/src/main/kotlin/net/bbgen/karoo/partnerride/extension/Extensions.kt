package net.bbgen.karoo.partnerride.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * Flow wrappers around [KarooSystemService] consumers (community-standard pattern).
 */

/** Stream any data type (built-in `DataType.Type.*` or `TYPE_EXT::<ext>::<typeId>`). */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(listenerId) }
}

fun KarooSystemService.streamRideState(): Flow<RideState> = callbackFlow {
    val listenerId = addConsumer { rideState: RideState -> trySendBlocking(rideState) }
    awaitClose { removeConsumer(listenerId) }
}

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> = callbackFlow {
    val listenerId = addConsumer { profile: UserProfile -> trySendBlocking(profile) }
    awaitClose { removeConsumer(listenerId) }
}

/** Rate-limit a stream for view updates (keeps latest value, emits at most every [timeout] ms). */
fun <T> Flow<T>.throttle(timeout: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(timeout)
}
