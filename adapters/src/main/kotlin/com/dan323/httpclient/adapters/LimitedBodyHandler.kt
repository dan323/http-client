package com.dan323.httpclient.adapters

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean

/** Collects a response body but stops reading at [max] bytes: the rest is cancelled and dropped, not buffered. */
internal class LimitedBodyHandler(private val max: Int) : HttpResponse.BodyHandler<ByteArray> {
    override fun apply(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray> = Subscriber(max)

    private class Subscriber(private val max: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val out = ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray>()
        private val done = AtomicBoolean()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (done.get()) return
            for (buffer in item) {
                val take = minOf(buffer.remaining(), max - out.size())
                if (take > 0) {
                    val chunk = ByteArray(take)
                    buffer.get(chunk)
                    out.write(chunk)
                }
                if (out.size() >= max) {
                    // enough: stop the transfer; whatever is left of the body is never read
                    if (done.compareAndSet(false, true)) { subscription?.cancel(); result.complete(out.toByteArray()) }
                    return
                }
            }
        }

        override fun onError(throwable: Throwable) { if (done.compareAndSet(false, true)) result.completeExceptionally(throwable) }

        override fun onComplete() { if (done.compareAndSet(false, true)) result.complete(out.toByteArray()) }
    }
}
