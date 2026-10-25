package com.b5solucoes.tcllight

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Two background decoders. Newly visible work passes speculative and previously queued cards. */
internal class ArtworkQueue(name: String) {
    private class Job(val key: String,var rank: Long,val action:()->Unit):Runnable,Comparable<Job> {
        override fun run()=action()
        override fun compareTo(other:Job)=rank.compareTo(other.rank)
    }
    private val jobs=PriorityBlockingQueue<Runnable>(16,Comparator { a,b->(a as Job).compareTo(b as Job) })
    private val worker=ThreadPoolExecutor(2,2,20L,TimeUnit.SECONDS,jobs) { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run() },name).apply { isDaemon=true }
    }
    private var urgent=0L
    private var speculative=0L
    @Synchronized fun execute(key: String,visible: Boolean=true,action:()->Unit) {
        worker.execute(Job(key,if(visible) --urgent else Long.MAX_VALUE/2+speculative++,action))
    }
    @Synchronized fun promote(key: String) {
        val job=jobs.firstOrNull { (it as Job).key==key } as? Job ?: return
        if(jobs.remove(job)) { job.rank=--urgent;jobs.add(job) }
    }
    fun close() { worker.shutdownNow();jobs.clear() }
}
