package org.kodein.internal

import org.kodein.*
import org.kodein.bindings.KodeinBinding
import org.kodein.bindings.Binding
import org.kodein.bindings.BindingKodein
import org.kodein.bindings.ExternalSource

/**
 * Container class where the bindings and their factories are stored.
 *
 * In kodein, every binding is stored as a factory.
 * Providers are special classes of factories that take Unit as parameter.
 *
 * @property _bindings The map containing all bindings.
 * @property _node See [KodeinContainerImpl.Node]
 */
internal class KodeinContainerImpl private constructor(
        private val _bindings: BindingsMap,
        override val externalSource: ExternalSource?,
        private val _node: Node? = null
) : KodeinContainer {

    /**
     * A cache that is filled each time a key is not found directly into the [_bindings] but by modifying the key's [Kodein.Key.argType].
     */
    private val _cache = HashMap<Kodein.Key<*, *>, KodeinBinding<*, *>>()

    /**
     * Class used to check for recursive dependencies, represents a node in the dependency tree.
     *
     * Each factory, in their Binding@getInstance methods receives a Kodein instance to enable transient dependency.
     * However, it is not the same kodein instance as the one used to get the main dependency.
     * Each time a transient dependency is needed, a new Kodein instance is constructed, with a new Node that has
     * the current Node as it's parent.
     * This allows, at each step, to walk up the node tree and check if the requested key has not yet been requested.
     * If the same key exists twice in the tree, it means that it has, and that there's a dependency recursion.
     *
     * @property _key The key of this node, meaning that this key has been looked for once.
     * @property _parent The parent node, meaning the parent lookup that needed this key.
     */
    private class Node(private val _key: Kodein.Key<*, *>, private val _overrideLevel: Int, private val _parent: Node?) {

        private fun displayString(key: Kodein.Key<*, *>, overrideLevel: Int) = if (overrideLevel != 0) "overridden ${key.bind}" else key.bind.toString()

        /**
         * Check that given key does **not** exist in the node tree or throws an exception if it does.
         *
         * @throws Kodein.DependencyLoopException if the key exists in the dependency tree.
         */
        internal fun check(searchedKey: Kodein.Key<*, *>, searchedOverrideLevel: Int) {
            if (!_check(searchedKey, searchedOverrideLevel)) {
                val list = _tree(searchedKey, searchedOverrideLevel) + displayString(searchedKey, _overrideLevel)
                val sb = StringBuilder()
                list.forEachIndexed { index, string ->
                    sb.append("  ")
                    if (index == 0)
                        sb.append("   ")
                    else if (index == 1) {
                        sb.append("  ╔╩>")
                    }
                    else {
                        sb.append("  ║")
                        sb.append("  ".repeat(index - 1))
                        sb.append("╚>")
                    }
                    sb.append(string)
                    sb.append("\n") // appendln does not exist in JS
                }
                sb.append("    ╚")
                sb.append("══".repeat(list.size - 1))
                sb.append("╝")
                throw Kodein.DependencyLoopException("Dependency recursion:\n$sb")
            }
        }

        /**
         * Recursive function that walks up the node tree to check if a specific key can be found.
         *
         * @return whether the given key exists in the tree.
         */
        private fun _check(searchedKey: Kodein.Key<*, *>, searchedOverrideLevel: Int): Boolean {
            return if (_key == searchedKey && _overrideLevel == searchedOverrideLevel) false else (_parent?._check(searchedKey, searchedOverrideLevel) ?: true)
        }

        /**
         * @return The current transitive dependency tree as a list of string.
         */
        private fun _tree(firstKey: Kodein.Key<*, *>, firstOverrideLevel: Int): List<String> {
            if (firstKey == _key && firstOverrideLevel == _overrideLevel)
                return listOf(displayString(_key, _overrideLevel))
            else
                return (_parent?._tree(firstKey, firstOverrideLevel) ?: emptyList()) + displayString(_key, _overrideLevel)
        }
    }

    /**
     * "Main" constructor that uses the bindings map configured by a [KodeinContainer.Builder].
     */
    internal constructor(builder: KodeinContainer.Builder, externalSource: ExternalSource?) : this(builder.bindings, externalSource)

    override val bindings: Map<Kodein.Key<*, *>, KodeinBinding<*, *>> get() = _bindings.bindings

    override val overriddenBindings: Map<Kodein.Key<*, *>, List<KodeinBinding<*, *>>> get() = _bindings.overrides

    /**
     * Finds a factory from a key, either in the [_bindings] or in the [_cache].
     *
     * @param key The key associated to the factory that is requested.
     * @return The factory, or null if non is found in both maps.
     */
    private fun _get(key: Kodein.Key<*, *>) : KodeinBinding<*, *>? {
        _bindings[key]?.let { return it }
        _cache[key]?.let { return it }
        return null
    }

    /**
     * Recursive function that tries to find a factory according to the provided key.
     *
     * 1. First, it of course tries with the [key] as is.
     * 2. Then, it tries with the [key] with it's [Kodein.Key.argType] set to the raw type if there is one.
     * 3. Then it goes back to 1, with the [key] with it's [Kodein.Key.argType] set te each implementing interfaces and their own interfaces
     * 4. Then it goes back to 1, with the [key] with it's [Kodein.Key.argType] set to the super type if there is one.
     * 5. If finally a factory is found, it puts it in the cache associated to the original key, so it will be directly found next time.
     *
     * @param key The key to look for
     * @param cache Whether the function needs to cache the result if a result is found (only the original key should be cached).
     * @return The found factory, or null if none is found.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <A, T: Any> _findBindingOrNull(key: Kodein.Key<A, T>, cache: Boolean) : KodeinBinding<A, T>? {
        _get(key)?.let { return it as KodeinBinding<A, T> }

        if (key.argType.isGeneric()) {
            _get(Kodein.Key(key.bind, key.argType.getRaw()))?.let {
                if (cache)
                    _cache[key] = it
                return it as KodeinBinding<A, T>
            }
        }

        for (argItfType in key.argType.getInterfaces()) {
            val found = _findBindingOrNull(Kodein.Key(key.bind, argItfType), false)
            if (found != null) {
                if (cache)
                    _cache[key] = found
                return found as KodeinBinding<A, T>
            }
        }

        val argSuperType = key.argType.getSuper()
        if (argSuperType == null || argSuperType == UnitToken)
            return null

        val found = _findBindingOrNull(Kodein.Key(key.bind, argSuperType), false)
        if (cache && found != null)
            _cache[key] = found
        return found as KodeinBinding<A, T>?
    }

    private fun <A, T: Any> _bindingKodein(key: Kodein.Key<A, T>, receiver: Any?, overrideLevel: Int) : BindingKodein {
        val container = KodeinContainerImpl(_bindings, externalSource, Node(key, overrideLevel, _node))
        return BindingKodeinImpl(KodeinImpl(container).direct, key, receiver, overrideLevel)
    }

    override fun <A, T: Any> factoryOrNull(key: Kodein.Key<A, T>, receiver: Any?): ((A) -> T)? {
        val bindingKodein = _bindingKodein(key, receiver, 0)

        _findBindingOrNull(key, true)?.let {
            _node?.check(key, 0)
            return it.getFactory(bindingKodein, key)
        }

        externalSource?.getFactory(bindingKodein, key)?.let {
            _node?.check(key, 0)
            @Suppress("UNCHECKED_CAST")
            return it as (A) -> T
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A, T: Any> overriddenFactoryOrNull(key: Kodein.Key<A, T>, receiver: Any?, overrideLevel: Int): ((A) -> T)? {
        val binding = _bindings.getOverride(key, overrideLevel) ?: return null
        val newOverrideLevel = overrideLevel + 1
        _node?.check(key, newOverrideLevel)
        return (binding as Binding<A, T>).getFactory(_bindingKodein(key, receiver, newOverrideLevel), key)
    }
}
