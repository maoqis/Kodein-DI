package org.kodein

/**
 * Transforms a factory function into a provider function by currying the factory with the given argument.
 *
 * @param A The type of argument the factory takes.
 * @param T The type of object to retrieve.
 * @receiver The factory to curry.
 * @param arg A function that provides the argument that will be passed to the factory.
 * @return A provider function that, when called, will call the receiver factory with the given argument.
 */
inline fun <A, T : Any> ((A) -> T).toProvider(crossinline arg: () -> A): () -> T = { invoke(arg()) }

@Suppress("NOTHING_TO_INLINE")
inline fun <T : Any> (() -> T).toUnitFactory(): (Unit) -> T = { _ -> invoke() }
