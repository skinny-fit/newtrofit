package newtrofit

import io.grpc.Channel
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class Newtrofit {

    private val cache = mutableMapOf<String, Any>()

    fun <T> create(
            clazz: Class<T>,
            channel: Channel,
            packageName: String
    ): Any {
        val service = clazz.annotations.filterIsInstance<Grpc>().getOrNull(0)?.let {
            it.name
        } ?: throw IllegalArgumentException("`${clazz.name}` is not `@Grpc` interface.")

        val invocationHandler = InvocationHandler { any, method, arrayOfAnys ->

            val annotation = (method.annotations.filterIsInstance<Sync>() +
                    method.annotations.filterIsInstance<Async>()).let {
                if (it.size > 1) {
                    throw IllegalArgumentException("`@Grpc` method only has one of `@Sync` or `@Async`.")
                }
                it.getOrNull(0)
            }

            val handler = fun(name: String): Any {
                val key = "$service.${method.name}.$name"

                val stub = cache[key] ?: let {
                    val grpcClass = Class.forName("$packageName.${service}Grpc")
                    val stubMethod = grpcClass.methods.first {
                        it.name == name
                    }
                    val instance = grpcClass.kotlin.objectInstance
                            ?: grpcClass.kotlin.java.newInstance()
                    val stub = stubMethod.invoke(
                            instance,
                            channel
                    )
                    cache[key] = stub
                    stub
                }

                val target = stub::class.java.methods.first {
                    it.name == method.name
                } ?: throw IllegalArgumentException("${method.name} not exist in stub.")

                return try {
                    target.invoke(stub, *(arrayOfAnys ?: emptyArray()))
                } catch (e: Exception) {
                    throw e
                }
            }

            return@InvocationHandler when (annotation) {
                is Sync -> handler("newBlockingStub")
                is Async -> handler("newStub")
                else -> throw IllegalArgumentException("`@Grpc` only has `@Sync` or `@Async` methods.")
            }
        }

        return Proxy.newProxyInstance(
                clazz.classLoader,
                arrayOf(clazz),
                invocationHandler
        )
    }
}
