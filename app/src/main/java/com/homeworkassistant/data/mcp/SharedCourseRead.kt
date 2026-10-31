package com.homeworkassistant.data.mcp

import kotlinx.coroutines.*

/** A cancelled observer does not cancel other observers; no orphan reads after the last observer leaves. */
internal class SharedCourseRead<T>(private val onJoin: () -> Unit = {}) {
    private class Flight<T>(val task: Deferred<T>, var observers: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flights = mutableMapOf<String, Flight<T>>()
    suspend fun run(key: String, block: suspend () -> T): T {
        val flight = synchronized(flights) {
            if (flights.containsKey(key)) onJoin()
            flights.getOrPut(key) { Flight(scope.async(start = CoroutineStart.LAZY) { block() }) }.also { it.observers++ }
        }
        try { return flight.task.await() } finally {
            synchronized(flights) {
                flight.observers--
                if (flight.observers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (flight.task.isActive) flight.task.cancel()
                }
            }
        }
    }
    fun clear() = synchronized(flights) { flights.values.forEach { it.task.cancel() }; flights.clear() }
}
