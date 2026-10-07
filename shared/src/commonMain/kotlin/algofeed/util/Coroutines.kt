package algofeed.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that suspends. Cancellation is rethrown, so a cancelled job stops instead of
 * carrying on with a failure that only says it was cancelled.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
