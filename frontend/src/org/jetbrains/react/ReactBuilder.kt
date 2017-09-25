/*
 * MUCtool Web Toolkit
 *
 * Copyright 2017 Alexander Orlov <alexander.orlov@loxal.net>. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jetbrains.react

import org.jetbrains.common.createInstance


@DslMarker
annotation class ReactDsl

open class ReactBuilder {
    open class Node<out P : RProps>(
            val type: Any,
            val props: P
    ) {
        var children: ArrayList<Any> = ArrayList()

        open val realType
            get() = type

        fun create(): ReactElement {
            return ReactWrapper.createRaw(realType, props, children)
        }
    }

    val path = mutableListOf<Node<*>>()
    private var lastLeaved: ReactElement? = null

    val children: ArrayList<Any>
        get() = currentNode().children

    fun currentNode(): Node<*> = path.last()
    inline fun <reified T : Node<*>> currentNodeOfType(): T = currentNode() as T

    fun <T : Node<*>> enterNode(node: T) {
        if (path.isEmpty() && lastLeaved != null) {
            console.error("React only allows single element be returned from render() function")
        }
        path.add(node)
    }

    fun exitCurrentNode(): ReactElement {
        val node = path.removeAt(path.lastIndex)
        val element = node.create()
        if (path.isNotEmpty()) {
            children.add(element)
        }
        lastLeaved = element
        return element
    }

    open fun <P : RProps> createReactNode(type: Any, props: P): Node<RProps> = Node(type, props)

    fun <P : RProps> enterReactNode(type: Any, props: P, handler: ReactBuilder.() -> Unit): ReactElement {
        enterNode(createReactNode(type, props))
        handler()
        return exitCurrentNode()
    }

    inline fun <reified P : RProps> instantiateProps(): P {
        return P::class.createInstance()
    }

    internal inline operator fun <reified T : ReactComponent<P, S>, reified P : RProps, S : RState> ReactComponentSpec<T, P, S>.invoke(
            noinline handler: P.() -> Unit = {}
    ): ReactElement {
        val props = instantiateProps<P>()
        return node(props) { props.handler() }
    }

    internal inline operator fun <reified T : ReactComponent<P, S>, reified P : RProps, S : RState> ReactComponentSpec<T, P, S>.invoke(
            props: P,
            noinline handler: P.() -> Unit = {}
    ): ReactElement {
        return node(props) { props.handler() }
    }

    inline fun <reified T : ReactComponent<P, S>, reified P : RProps, S : RState> ReactComponentSpec<T, P, S>.node(
            props: P,
            noinline handler: ReactBuilder.() -> Unit = {}
    ) = enterReactNode(ReactComponent.wrap(T::class), props, handler)

    internal inline operator fun <reified P : RProps> ReactExternalComponentSpec<P>.invoke(
            noinline handler: P.() -> Unit = {}
    ): ReactElement {
        val props = instantiateProps<P>()
        return node(props) { props.handler() }
    }

    inline fun <reified P : RProps> ReactExternalComponentSpec<P>.node(
            props: P,
            noinline handler: ReactBuilder.() -> Unit = {}
    ) = enterReactNode(ref, props, handler)

    fun result(): ReactElement? {
        return lastLeaved
    }
}
